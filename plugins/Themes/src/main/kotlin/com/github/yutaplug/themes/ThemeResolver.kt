package com.github.yutaplug.themes

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.SparseArray
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.aliucord.Constants
import com.aliucord.Utils
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

/**
 * Flattens the enabled themes into a [ThemeState], following Themer's semantics:
 * - themes layer in enabled order, later ones winning key by key;
 * - within a theme: `simple_colors` first, then `colors`, then `drawable_tints`;
 * - `simple_colors` keys expand into the same color, attribute and tint lists as Themer.
 *
 * Unlike Themer, everything is keyed by resource id. Themer's by-value swap is kept only as a
 * fallback ([ThemeState.valueMap]) for colors that reach the screen without an id.
 */
internal object ThemeResolver {
    private class Collected {
        val colors = LinkedHashMap<String, Int>()
        val attrs = LinkedHashMap<String, Int>()
        val tints = LinkedHashMap<String, Int>()
        val specials = LinkedHashMap<String, Int>()
        val fonts = LinkedHashMap<String, String>()
        val raws = LinkedHashMap<String, String>()
        var background: JSONObject? = null
        val warnings = ArrayList<String>()
    }

    /**
     * Builds the state for [themes]. With [download] false, nothing touches the network and missing
     * files are listed in [ThemeState.pendingDownloads]. Must run with [ColorEngine] bypassed for the
     * calling thread so that original resource values are read.
     */
    fun resolve(
        context: Context,
        themes: List<ThemeFile>,
        isLight: Boolean,
        baseStyle: Int,
        download: Boolean,
    ): ThemeState {
        if (themes.isEmpty()) return ThemeState.EMPTY
        val c = Collected()
        val mappings = attrMappings(isLight)
        themes.forEach { theme ->
            try {
                theme.convertIfLegacy()
                collect(theme, theme.json(), mappings, c)
            } catch (error: Throwable) {
                c.warnings += "${theme.name}: failed to load (${error.message})"
                logger.error("Failed to load theme ${theme.name}", error)
            }
        }
        return ColorEngine.bypass { build(context, c, baseStyle, download) }
    }

    private fun collect(theme: ThemeFile, json: JSONObject, mappings: Map<String, List<String>>, c: Collected) {
        fun warn(message: String) {
            c.warnings += "${theme.name}: $message"
        }

        fun putAttrsFor(name: String, color: Int) = mappings[name]?.forEach { c.attrs[it] = color }

        fun putColor(name: String, color: Int) {
            if (name in SPECIAL_COLORS) {
                c.specials[name] = color
                SPECIAL_ATTRS[name]?.forEach { c.attrs[it] = color }
            } else {
                c.colors[name] = color
            }
        }

        fun colorsOf(section: String, each: (String, Int) -> Unit) {
            val obj = json.optJSONObject(section) ?: return
            obj.keys().forEach { key ->
                val color = parseColor(obj.opt(key))
                if (color == null) warn("invalid color for $section.$key") else each(key, color)
            }
        }

        json.optJSONObject("background")?.let { if (it.has("url")) c.background = it }
        json.optJSONObject("fonts")?.let { fonts -> fonts.keys().forEach { c.fonts[it] = fonts.getString(it) } }
        json.optJSONObject("raws")?.let { raws -> raws.keys().forEach { c.raws[it] = raws.getString(it) } }

        colorsOf("simple_colors") { key, v ->
            when (key) {
                "accent" -> {
                    SIMPLE_ACCENT_NAMES.forEach { putColor(it, v) }
                    SIMPLE_ACCENT_ATTRS.forEach { putSimpleAttr(c, it, v) }
                    c.tints["ic_nitro_rep"] = v
                    c.tints["drawable_voice_indicator_speaking"] = v
                }

                "background" -> {
                    SIMPLE_BG_NAMES.forEach { putColor(it, v) }
                    SIMPLE_BG_ATTRS.forEach { putSimpleAttr(c, it, v) }
                }

                "background_secondary" -> {
                    SIMPLE_BG_SECONDARY_NAMES.forEach { putColor(it, v) }
                    SIMPLE_BG_SECONDARY_ATTRS.forEach { putSimpleAttr(c, it, v) }
                    CHANNEL_OVERLAYS.forEach { c.tints[it] = v }
                }

                "mention_highlight" -> {
                    putColor("status_yellow_500", v or (0xff shl 24))
                    putAttrsFor(key, v)
                }

                "active_channel" -> {
                    CHANNEL_OVERLAYS.forEach { c.tints[it] = v }
                    putColor(key, v)
                }

                "statusbar", "input_background", "blocked_bg" -> {
                    putColor(key, v)
                }

                else -> {
                    warn("unknown simple color $key")
                }
            }
        }
        colorsOf("colors") { key, v ->
            if (key == "brand_500") c.tints["ic_nitro_rep"] = v
            putColor(key, v)
            putAttrsFor(key, v)
        }
        colorsOf("drawable_tints") { key, v -> c.tints[key] = v }
    }

    private fun putSimpleAttr(c: Collected, name: String, color: Int) {
        val prefix = "__alpha_10_"
        if (name.startsWith(prefix)) {
            c.attrs[name.removePrefix(prefix)] = (color and 0x00ffffff) or (0x1a shl 24)
        } else {
            c.attrs[name] = color
        }
    }

    private fun build(context: Context, c: Collected, baseStyle: Int, download: Boolean): ThemeState {
        val res = context.resources
        val warnings = c.warnings
        val pending = ArrayList<String>()
        val value = TypedValue()

        val colorById = SparseIntArray()
        val selectorById = SparseIntArray()
        val valueMap = SparseIntArray()
        c.colors.forEach { (name, color) ->
            val id = Utils.getResId(name, "color")
            if (id == 0) {
                warnings += "Unknown color $name"
                return@forEach
            }
            val original = try {
                res.getValue(id, value, true)
                value.type to value.data
            } catch (error: Throwable) {
                null
            }
            if (original != null && original.first in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                colorById.put(id, color)
                val v = original.second
                val shared = v in ThemeState.UNLINKED && name != "white" && name != "black"
                if (v ushr 24 != 0 && !shared) valueMap.put(v, color)
            } else {
                // A res/color selector: recolored by default state, keeping its other states.
                selectorById.put(id, color)
            }
        }

        val attrById = SparseIntArray()
        val theme = ContextThemeWrapper(context, baseStyle).theme
        val attrTargets = SparseIntArray()
        c.attrs.forEach { (name, color) ->
            val id = Utils.getResId(name, "attr")
            if (id == 0) {
                warnings += "Unknown attribute $name"
                return@forEach
            }
            attrById.put(id, color)
            // Layouts reach attributes through the color they point at; follow that color too,
            // unless it is one shared by unrelated UI.
            if (theme.resolveAttribute(id, value, true) &&
                value.resourceId != 0 &&
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT &&
                value.data !in ThemeState.UNLINKED
            ) {
                attrTargets.put(value.resourceId, color)
            }
        }
        for (i in 0 until attrTargets.size()) {
            val id = attrTargets.keyAt(i)
            if (colorById.indexOfKey(id) < 0) colorById.put(id, attrTargets.valueAt(i))
        }

        val tintById = SparseIntArray()
        c.tints.forEach { (name, color) ->
            val id = Utils.getResId(name, "drawable")
            if (id == 0) warnings += "Unknown drawable $name" else tintById.put(id, color)
        }

        fun file(url: String, what: String): File? = try {
            if (download) ThemeStore.fetch(url) else ThemeStore.localFile(url) ?: null.also { pending += url }
        } catch (error: Throwable) {
            warnings += "Failed to load $what: ${error.message}"
            null
        }

        val fontById = SparseArray<Typeface>()
        var defaultFont: Typeface? = null
        if (ThemeStore.customFonts) {
            val fontIds = fontIds()
            c.fonts.forEach { (name, url) ->
                val id = if (name == "*") -1 else fontIds[name]
                if (id == null) {
                    warnings += "Unknown font $name"
                    return@forEach
                }
                val typeface = file(url, "font $name")?.let {
                    runCatching { Typeface.createFromFile(it) }
                        .onFailure { error ->
                            warnings += "Font $name is not a valid font file (${error.message})"
                        }.getOrNull()
                } ?: return@forEach
                if (id == -1) defaultFont = typeface else fontById.put(id, typeface)
            }
        }

        val rawById = SparseArray<File>()
        if (ThemeStore.customSounds) {
            c.raws.forEach { (name, url) ->
                val id = Utils.getResId(name, "raw")
                if (id == 0) {
                    warnings += "Unknown sound $name"
                    return@forEach
                }
                file(url, "sound $name")?.let { rawById.put(id, it) }
            }
        }

        var wallpaper: android.graphics.Bitmap? = null
        var animated: Uri? = null
        var overlayAlpha = 0
        val bg = c.background
        if (bg != null) {
            val url = bg.optString("url")
            val alpha = bg.optInt("overlay_alpha", DEFAULT_OVERLAY_ALPHA)
            val blur = bg.optDouble("blur_radius", 0.0).takeIf { !it.isNaN() } ?: 0.0
            if (alpha !in 0..0xff) {
                warnings += "overlay_alpha must be 0-255, was $alpha"
            } else if (blur !in 0.0..25.0) {
                warnings += "blur_radius must be 0-25, was $blur"
            } else if (url.isNotEmpty() && alpha != 0xff) {
                file(url, "background")?.let { source ->
                    if (url.substringBefore('?').endsWith(".gif", true)) {
                        animated = Uri.fromFile(source)
                        overlayAlpha = alpha
                    } else {
                        wallpaper = runCatching { Wallpaper.prepare(source, url, alpha, blur) }
                            .onFailure { warnings += "Failed to load background: ${it.message}" }
                            .getOrNull()
                    }
                }
            }
        }

        val outputs = HashSet<Int>()
        listOf(colorById, selectorById, attrById, valueMap).forEach { map ->
            for (i in 0 until map.size()) outputs += map.valueAt(i)
        }
        c.specials.values.forEach { outputs += it }

        return ThemeState(
            colorById = colorById,
            selectorById = selectorById,
            attrById = attrById,
            valueMap = valueMap,
            outputs = outputs.toIntArray().apply { sort() },
            tintById = tintById,
            fontById = fontById,
            defaultFont = defaultFont,
            rawById = rawById,
            statusBar = c.specials["statusbar"],
            inputBackground = c.specials["input_background"],
            blockedBackground = c.specials["blocked_bg"],
            wallpaper = wallpaper,
            animatedWallpaper = animated,
            overlayAlpha = overlayAlpha,
            warnings = warnings.distinct(),
            pendingDownloads = pending.distinct(),
        )
    }

    /** Font names Themer accepts, from Aliucord's [Constants.Fonts]. */
    fun fontIds(): Map<String, Int> = Constants.Fonts::class.java.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                Modifier.isPublic(it.modifiers) &&
                it.type == Int::class.javaPrimitiveType
        }.associate { it.name to it.getInt(null) }
}
