package com.github.yutaplug.themes.settings

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.github.yutaplug.themes.formatColor
import com.lytefast.flexinput.R

/**
 * Browses every name a theme section can set, with a live search and a preview of Discord's own
 * color or icon. Tapping a name opens the color picker; the pick is handed to [onPicked]. With a
 * [chooser], the tapped name goes to it instead, and it calls `done` once something was picked; the
 * page then closes, right away or as soon as it is back in front.
 */
internal class NamePickerPage(
    private val type: ColorListPage.Type,
    private val names: () -> List<String>,
    private val existing: Set<String>,
    private val onPicked: (name: String, color: Int) -> Unit,
    private val subtitle: String = type.title,
    private val pageTitle: String = if (type == ColorListPage.Type.DRAWABLES) "Add drawable" else "Add color",
    private val hint: String = "tap one to pick its color",
    private val chooser: ((name: String, page: NamePickerPage, done: () -> Unit) -> Unit)? = null,
) : SettingsPage() {
    private var all: List<String> = emptyList()
    private var shown: List<String> = emptyList()
    private var count: TextView? = null
    private val adapter = Adapter()
    private var finished = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // The list is long (thousands of drawables), so it must recycle rows instead of scrolling a layout.
        removeScrollView()
        setActionBarTitle(pageTitle)
        setActionBarSubtitle(subtitle)
        val ctx = requireContext()
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundPrimary))

        all = names().filter { it !in existing }
        shown = all
        val p = DimenUtils.dpToPx(16)
        val search = TextInput(ctx, "Search ${all.size} names")
        search.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(editable: Editable?) = filter(editable?.toString().orEmpty())
        })
        linearLayout.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(p, p / 2, p, p / 2) })
        count = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply { background = null }
        linearLayout.addView(count, LinearLayout.LayoutParams(-1, -2))
        linearLayout.addView(
            RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                adapter = this@NamePickerPage.adapter
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        updateCount()
    }

    private fun filter(query: String) {
        val q = query.trim()
        shown = if (q.isEmpty()) all else all.filter { it.contains(q, true) }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        count?.text = when {
            all.isEmpty() -> "Everything here is already in the theme."
            shown.isEmpty() -> "No matches."
            else -> "${shown.size} of ${all.size} · $hint"
        }
    }

    private fun pick(name: String) {
        chooser?.let {
            it(name, this) { finish() }
            return
        }
        val ctx = requireContext()
        showColorPicker(ctx, parentFragmentManager, startColor(ctx, type, name)) { color ->
            onPicked(name, color)
            close()
        }
    }

    /**
     * Closes the page; when another page is open on top, once this one is back in front. Tracked
     * here because pages live inside Aliucord's FragmentProxy, which forwards onResume and onPause
     * but never attaches the page itself, so its own isResumed stays false.
     */
    private fun finish() {
        finished = true
        if (inFront) close()
    }

    private var inFront = false

    override fun onResume() {
        super.onResume()
        inFront = true
        if (finished) Utils.mainThread.post { close() }
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    private class Row(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val subtitle: TextView,
    ) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val ctx = parent.context
            val size = DimenUtils.dpToPx(32)
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = DimenUtils.dpToPx(4)
                setPadding(pad, pad, pad, pad)
            }
            val title = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                background = null
                setPaddingRelative(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), 0)
            }
            val subtitle = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                background = null
                setPaddingRelative(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                val ripple = TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
                addView(icon, LinearLayout.LayoutParams(size, size).apply { marginStart = DimenUtils.dpToPx(16) })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return Row(root, icon, title, subtitle)
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val name = shown[position]
            val ctx = holder.root.context
            holder.title.text = name
            holder.root.setOnClickListener { pick(name) }
            holder.root.contentDescription = name
            if (type == ColorListPage.Type.DRAWABLES) {
                holder.icon.background = null
                holder.icon.setImageDrawable(originalDrawable(ctx, name))
                holder.subtitle.visibility = View.GONE
                return
            }
            val color = originalColor(ctx, type, name)
            holder.icon.setImageDrawable(null)
            holder.icon.background = color?.let {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(it)
                    setStroke(DimenUtils.dpToPx(1), ColorCompat.getThemedColor(ctx, R.b.colorBackgroundModifierAccent))
                }
            }
            val description = if (type == ColorListPage.Type.SIMPLE) SIMPLE_DESCRIPTIONS[name] else null
            val subtitle = listOfNotNull(description, color?.let { "Default ${formatColor(it)}" })
            holder.subtitle.text = subtitle.joinToString(" · ")
            holder.subtitle.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
