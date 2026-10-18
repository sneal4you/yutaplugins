package com.github.yutaplug.themes.settings

import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.aliucord.views.ToolbarButton
import com.discord.utilities.color.ColorCompat
import com.github.yutaplug.themes.ALLOWED_RESOURCE_DOMAINS
import com.github.yutaplug.themes.IconDrawable
import com.github.yutaplug.themes.IconEntry
import com.github.yutaplug.themes.ThemeIcon
import com.github.yutaplug.themes.ThemeStore
import com.lytefast.flexinput.R
import org.json.JSONObject

/** Lists a theme's `icons` section: which Discord drawables are replaced by which image. */
internal class IconListPage(private val data: JSONObject, private val names: () -> List<String>) : SettingsPage() {
    private var filter = ""
    private var rows: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(TITLE)
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
        note(
            "Replace any Discord icon with another Discord icon, or with a PNG, JPEG, WebP or GIF image. " +
                "Replacements are tinted like Discord's own icons, so a white icon on a transparent " +
                "background works best. Turn on \"keep colors\" for colored ones.",
        )
        action("Add icon", "Browse all drawables, then pick another icon or an image") { addEntry() }
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
        val entry = IconEntry.parse(data.opt(key))
        val size = DimenUtils.dpToPx(48)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val ripple = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            setOnClickListener { showActions(key) }

            addView(
                ToolbarButton(ctx).apply {
                    ContextCompat
                        .getDrawable(ctx, Utils.getResId("ic_x_red_24dp", "drawable"))
                        ?.let { setImageDrawable(it, false) }
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
                            text = entry?.let {
                                listOfNotNull(
                                    if (it.isDrawable) "Discord icon ${it.source}" else it.source,
                                    "Keeps its colors".takeIf { _ -> it.keepColors },
                                ).joinToString("\n")
                            } ?: "Invalid: ${data.opt(key)}"
                            background = null
                            setPaddingRelative(DimenUtils.dpToPx(8), 0, 0, DimenUtils.dpToPx(8))
                        },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )

            val previewSize = DimenUtils.dpToPx(32)
            listOf(originalDrawable(ctx, key), entry?.let { preview(it, key, previewSize) }).forEach { drawable ->
                addView(
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(drawable)
                    },
                    LinearLayout.LayoutParams(previewSize, previewSize).apply { marginEnd = DimenUtils.dpToPx(16) },
                )
            }
        }
    }

    /** The replacement: the other Discord icon, or the image once it is on the device. */
    private fun preview(entry: IconEntry, key: String, size: Int): Drawable? {
        if (entry.isDrawable) return originalDrawable(requireContext(), entry.source)
        val file = ThemeStore.localFile(entry.source) ?: return null
        val bitmap = ThemeIcon(key, file, 0, entry.keepColors).bitmap(size) ?: return null
        return IconDrawable(bitmap, size, size, entry.keepColors).apply {
            setTint(ColorCompat.getThemedColor(requireContext(), R.b.colorInteractiveNormal))
        }
    }

    private fun showActions(key: String) {
        val entry = IconEntry.parse(data.opt(key))
        val keep = entry?.keepColors == true
        SelectDialog().apply {
            title = key
            items = arrayOf(
                "Use another Discord icon",
                "Use an image",
                if (keep) "Tint like Discord's icons" else "Keep its own colors",
                "Remove",
            )
            onResultListener = { which ->
                val update = { source: String ->
                    data.put(key, IconEntry(source, keep).toJson())
                    renderRows()
                }
                when (which) {
                    0 -> {
                        pickDrawable(this@IconListPage, key) { update(it) }
                    }

                    1 -> {
                        val initial = entry?.takeIf { !it.isDrawable }?.source.orEmpty()
                        askImage(this@IconListPage.parentFragmentManager, key, initial) { update(it) }
                    }

                    2 -> {
                        if (entry != null) {
                            data.put(key, IconEntry(entry.source, !keep).toJson())
                            renderRows()
                        }
                    }

                    3 -> {
                        confirmRemove(key)
                    }
                }
            }
        }.show(parentFragmentManager, "ThemesIconActions")
    }

    private fun confirmRemove(key: String) {
        ConfirmDialog()
            .apply {
                setTitle("Remove $key?")
                setDescription("The theme will no longer replace this icon.")
                setIsDangerous(true)
                setOnOkListener {
                    data.remove(key)
                    dismiss()
                    renderRows()
                }
            }.show(parentFragmentManager, "ThemesRemoveIcon")
    }

    private fun addEntry() {
        val existing = data.keys().asSequence().toSet()
        // The new entry shows up when this page resumes.
        val picker = NamePickerPage(
            ColorListPage.Type.DRAWABLES,
            names,
            existing,
            onPicked = { _, _ -> },
            subtitle = TITLE,
            pageTitle = "Add icon",
            hint = "tap the one to replace",
            chooser = { name, page, done ->
                val put = { source: String ->
                    data.put(name, IconEntry(source, false).toJson())
                    done()
                }
                SelectDialog().apply {
                    title = "Replace $name with"
                    items = arrayOf("Another Discord icon", "An image")
                    onResultListener = { which ->
                        if (which == 0) {
                            pickDrawable(page, name, put)
                        } else {
                            askImage(page.parentFragmentManager, name, "", put)
                        }
                    }
                }.show(page.parentFragmentManager, "ThemesIconSource")
            },
        )
        Utils.openPageWithProxy(requireContext(), picker)
    }

    /** Opens the list of Discord drawables to pick the one shown instead of [name]. */
    private fun pickDrawable(from: SettingsPage, name: String, onPicked: (String) -> Unit) {
        val picker = NamePickerPage(
            ColorListPage.Type.DRAWABLES,
            names,
            setOf(name),
            onPicked = { _, _ -> },
            subtitle = name,
            pageTitle = "Pick replacement",
            hint = "tap one to show it instead",
            chooser = { target, _, done ->
                onPicked(target)
                done()
            },
        )
        Utils.openPageWithProxy(from.requireContext(), picker)
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    override fun onDestroyView() {
        rows = null
        super.onDestroyView()
    }

    companion object {
        private const val TITLE = "Icons"

        /** Asks for the image link of [name]; invalid links keep the dialog open. */
        fun askImage(fragments: FragmentManager, name: String, initial: String, onPicked: (String) -> Unit) {
            val dialog = InputDialog()
            dialog
                .setTitle("Image for $name")
                .setDescription(
                    "An https link on ${ALLOWED_RESOURCE_DOMAINS.joinToString()}, or a file:// path. " +
                        "PNG, JPEG, WebP or GIF.",
                ).setPlaceholderText("Image link")
                .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                .setOnOkListener {
                    val url = dialog.input.trim()
                    if (!Validators.url(name, url)) {
                        Utils.showToast("Use an https link on an allowed site, or an existing file:// path")
                        return@setOnOkListener
                    }
                    dialog.dismiss()
                    onPicked(url)
                }
            if (initial.isNotEmpty()) {
                dialog.setOnDialogShownListener { dialog.inputLayout.editText?.setText(initial) }
            }
            dialog.show(fragments, "ThemesIconLink")
        }
    }
}
