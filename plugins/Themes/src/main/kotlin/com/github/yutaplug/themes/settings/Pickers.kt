package com.github.yutaplug.themes.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.discord.utilities.colors.ColorPickerUtils
import com.github.yutaplug.themes.BLOCKED_COLOR_DARK
import com.github.yutaplug.themes.ColorEngine

/** What each `simple_colors` key changes, shown in the name picker. */
internal val SIMPLE_DESCRIPTIONS = mapOf(
    "accent" to "Buttons, links, mentions and other brand colors",
    "background" to "Main chat and panel background",
    "background_secondary" to "Channel list, input box and secondary panels",
    "mention_highlight" to "Messages that mention you",
    "active_channel" to "Highlight of the selected channel",
    "statusbar" to "Status bar",
    "input_background" to "Text input background",
    "blocked_bg" to "Background of blocked messages",
)

/** A Discord color that shows each simple key's default look in dark mode. */
private val SIMPLE_SAMPLES = mapOf(
    "accent" to "brand_new_500",
    "background" to "primary_dark_600",
    "background_secondary" to "primary_dark_630",
    "mention_highlight" to "status_yellow_500",
    "active_channel" to "primary_dark_660",
    "statusbar" to "primary_dark_800",
    "input_background" to "primary_dark_700",
)

/** The color Discord uses for [name] without themes, or null when unknown. */
internal fun originalColor(context: Context, type: ColorListPage.Type, name: String): Int? = runCatching {
    when (type) {
        ColorListPage.Type.COLORS -> resourceColor(context, name)

        ColorListPage.Type.SIMPLE -> when (name) {
            "blocked_bg" -> BLOCKED_COLOR_DARK
            else -> SIMPLE_SAMPLES[name]?.let { resourceColor(context, it) }
        }

        ColorListPage.Type.DRAWABLES -> null
    }
}.getOrNull()

private fun resourceColor(context: Context, name: String): Int? {
    val id = Utils.getResId(name, "color")
    if (id == 0) return null
    return ColorEngine.bypass { ContextCompat.getColorStateList(context, id)?.defaultColor }
}

/** The drawable as Discord ships it, with the theme turned off, or null. */
internal fun originalDrawable(context: Context, name: String): Drawable? {
    val id = Utils.getResId(name, "drawable")
    if (id == 0) return null
    return runCatching { ColorEngine.bypass { ContextCompat.getDrawable(context, id) } }.getOrNull()?.mutate()
}

/** Discord's own color picker, with the transparency slider enabled. */
internal fun showColorPicker(context: Context, fragments: FragmentManager, initial: Int, onPicked: (Int) -> Unit) {
    val title = Utils.getResId("custom_color", "string")
    val picker = ColorPickerUtils.INSTANCE.buildColorPickerDialog(context, title, initial)
    picker.arguments?.putBoolean("alpha", true)
    picker.k = object : b.k.a.a.f {
        override fun onColorReset(color: Int) {}

        // Signed ints, which is what Aliucord's Themer reads.
        override fun onColorSelected(id: Int, color: Int) = onPicked(color)

        override fun onDialogDismissed(id: Int) {}
    }
    picker.show(fragments, "ThemesColorPicker")
}

internal fun startColor(context: Context, type: ColorListPage.Type, name: String) =
    originalColor(context, type, name) ?: if (type == ColorListPage.Type.DRAWABLES) Color.WHITE else Color.BLACK
