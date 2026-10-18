package com.github.yutaplug.themes.settings

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.lytefast.flexinput.R

/** Settings rows built from Discord's own settings styles (as in FakeDecorSettings). */
internal fun SettingsPage.section(title: String) {
    add(
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = title
            setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
        },
    )
}

internal fun SettingsPage.note(text: CharSequence): TextView =
    TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
        this.text = text
        background = null
        setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
        add(this)
    }

internal fun SettingsPage.action(title: String, subtitle: String?, onClick: () -> Unit): View {
    val row = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        }
        contentDescription = if (subtitle == null) title else "$title. $subtitle"
        isFocusable = true
        setOnClickListener { onClick() }
    }
    row.addView(
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
            text = title
            background = null
            val bottom = if (subtitle == null) DimenUtils.dpToPx(8) else 0
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, bottom)
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    if (subtitle != null) {
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = subtitle
                background = null
                setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    add(row)
    return row
}

internal fun SettingsPage.divider() {
    add(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), DimenUtils.dpToPx(1))
}

internal fun SettingsPage.add(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
    linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
}
