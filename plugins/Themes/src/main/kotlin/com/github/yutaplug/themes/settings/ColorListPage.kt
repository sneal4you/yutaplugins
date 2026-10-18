package com.github.yutaplug.themes.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.aliucord.views.ToolbarButton
import com.discord.utilities.color.ColorCompat
import com.github.yutaplug.themes.formatColor
import com.github.yutaplug.themes.parseColor
import com.lytefast.flexinput.R
import org.json.JSONObject

/** Lists one color section of a theme (`simple_colors`, `colors` or `drawable_tints`) for editing. */
class ColorListPage(
    private val type: Type,
    private val data: JSONObject,
    private val names: () -> List<String>,
) : SettingsPage() {
    enum class Type(val title: String) {
        SIMPLE("Simple colors"),
        COLORS("Colors"),
        DRAWABLES("Drawable tints"),
    }

    private var filter = ""
    private var rows: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(type.title)
        setActionBarSubtitle("Theme Editor")
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))

        val p = DimenUtils.dpToPx(16)
        val search = TextInput(requireContext(), "Search", filter)
        search.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(editable: Editable?) {
                filter = editable?.toString().orEmpty()
                renderRows()
            }
        })
        linearLayout.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(p, p / 2, p, p / 2) })
        val what = if (type == Type.DRAWABLES) "drawable" else "color"
        action("Add $what", "Browse all names, then pick a color") { addEntry() }
        divider()
        rows = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }.also { add(it) }
        renderRows()
    }

    private fun keys() = data.keys().asSequence().toList().sortedBy { it.lowercase() }

    private fun renderRows() {
        val container = rows ?: return
        container.removeAllViews()
        val shown = keys().filter { it.contains(filter, true) }
        if (shown.isEmpty()) {
            container.addView(
                TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    text = if (data.length() == 0) "Nothing here yet." else "No matches."
                },
            )
        }
        shown.forEach { key -> container.addView(row(key)) }
    }

    private fun row(key: String): View {
        val ctx = requireContext()
        val color = parseColor(data.opt(key))
        val size = DimenUtils.dpToPx(48)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ripple = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            setOnClickListener { pickColor(key, color ?: Color.BLACK) }

            addView(
                ToolbarButton(ctx).apply {
                    ContextCompat
                        .getDrawable(
                            ctx,
                            Utils.getResId("ic_x_red_24dp", "drawable"),
                        )?.let { setImageDrawable(it, false) }
                    contentDescription = "Remove $key"
                    val pad = DimenUtils.dpToPx(12)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { confirmRemove(key) }
                },
                LinearLayout.LayoutParams(size, size).apply { marginStart = DimenUtils.dpToPx(8) },
            )

            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                            text = key
                            background = null
                            setPaddingRelative(DimenUtils.dpToPx(8), DimenUtils.dpToPx(8), 0, 0)
                        },
                    )
                    addView(
                        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                            text = color?.let { formatColor(it) } ?: "Invalid: ${data.opt(key)}"
                            background = null
                            setPaddingRelative(DimenUtils.dpToPx(8), 0, 0, DimenUtils.dpToPx(8))
                        },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )

            val previewSize = DimenUtils.dpToPx(if (type == Type.DRAWABLES) 36 else 32)
            addView(
                preview(key, color ?: Color.TRANSPARENT),
                LinearLayout.LayoutParams(previewSize, previewSize).apply { marginEnd = DimenUtils.dpToPx(16) },
            )
        }
    }

    /** The drawable tinted with its color for drawable tints, else a color swatch. */
    private fun preview(key: String, color: Int): View {
        val ctx = requireContext()
        if (type == Type.DRAWABLES) {
            // Loaded with the theme off, so the preview shows only this entry's tint.
            val drawable = originalDrawable(ctx, key)
            if (drawable != null) {
                return ImageView(ctx).apply {
                    setImageDrawable(drawable.apply { setTint(color) })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = DimenUtils.dpToPx(6)
                    setPadding(pad, pad, pad, pad)
                    // A ring keeps icons visible when their tint is close to the page color.
                    background = circle(Color.TRANSPARENT, outline(color))
                    contentDescription = "$key preview"
                }
            }
        }
        return View(ctx).apply { background = circle(color, outline(color)) }
    }

    /** The color itself when it stands out from the page, else white or black for contrast. */
    private fun outline(color: Int): Int {
        val page = ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary) or (0xff shl 24)
        val opaque = color or (0xff shl 24)
        return when {
            ColorUtils.calculateContrast(opaque, page) > 2.0 && Color.alpha(color) == 0xff -> opaque
            Color.luminance(page) < 0.5f -> Color.WHITE
            else -> Color.BLACK
        }
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(DimenUtils.dpToPx(1), stroke)
    }

    private fun confirmRemove(key: String) {
        ConfirmDialog()
            .apply {
                setTitle("Remove $key?")
                setDescription(
                    "The theme will no longer change this ${if (type == Type.DRAWABLES) "drawable" else "color"}.",
                )
                setIsDangerous(true)
                setOnOkListener {
                    data.remove(key)
                    dismiss()
                    renderRows()
                }
            }.show(parentFragmentManager, "ThemesRemoveColor")
    }

    private fun addEntry() {
        val existing = data.keys().asSequence().toSet()
        // The new entry shows up when this page resumes.
        val picker = NamePickerPage(type, names, existing, onPicked = { name, color -> data.put(name, color) })
        Utils.openPageWithProxy(requireContext(), picker)
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    private fun pickColor(key: String, initial: Int) {
        showColorPicker(requireContext(), parentFragmentManager, initial) { color ->
            data.put(key, color)
            renderRows()
        }
    }

    override fun onDestroyView() {
        rows = null
        super.onDestroyView()
    }
}
