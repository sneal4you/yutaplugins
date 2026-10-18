package com.github.yutaplug.themes

import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Themes whose background image points at Discord's CDN. Those links now expire, so the background
 * no longer loads and the theme, usually built around it, is broken. Fixed copies of many such
 * themes are kept in the Aliucord theme mirror.
 *
 * Fonts and sounds on Discord's CDN don't count: the theme still works without them, they are just
 * skipped (some mirror copies still have dead sound links).
 */
internal object DeadThemes {
    const val MIRROR_URL = "https://gitlab.com/Grzesiek11/aliucord-theme-mirror"
    private const val MIRROR_API = "https://gitlab.com/api/v4/projects/Grzesiek11%2Faliucord-theme-mirror"
    private const val MIRROR_RAW = "$MIRROR_URL/-/raw/master/themes/"

    private val discordCdn = Pattern.compile(
        "^https?://(www\\.)?(cdn\\.discordapp\\.com|media\\.discordapp\\.net|cdn\\.discord\\.com|media\\.discord\\.net)/",
        Pattern.CASE_INSENSITIVE,
    )

    /** Normalized theme name to mirror file name; null until first fetched. */
    @Volatile
    private var mirrorIndex: Map<String, String>? = null

    fun isDiscordUrl(url: String) = discordCdn.matcher(url.trim()).find()

    /** Whether the theme's background image is a Discord CDN link (legacy flat themes included). */
    fun hasDiscordBackground(json: JSONObject): Boolean {
        val url = json.optJSONObject("background")?.optString("url") ?: json.optString("background_url")
        return isDiscordUrl(url.orEmpty())
    }

    fun uses(theme: ThemeFile) = runCatching { hasDiscordBackground(theme.json()) }.getOrDefault(false)

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** Lists the mirror's themes. Blocking; cached for the session. */
    private fun index(): Map<String, String> {
        mirrorIndex?.let { return it }
        val index = HashMap<String, String>()
        var page = 1
        while (page <= MAX_PAGES) {
            val text = Http.simpleGet("$MIRROR_API/repository/tree?path=themes&per_page=100&page=$page")
            val entries = JSONArray(text)
            if (entries.length() == 0) break
            for (i in 0 until entries.length()) {
                val name = entries.getJSONObject(i).optString("name")
                if (name.endsWith(".json")) index[normalize(name.removeSuffix(".json"))] = name
            }
            page++
        }
        mirrorIndex = index
        return index
    }

    /**
     * The mirror's raw URL for [theme], matched by file or theme name. Discord attachment names swap
     * spaces for underscores, so names are compared by letters and digits only. Blocking.
     */
    fun mirrorFor(theme: ThemeFile): String? {
        val index = index()
        val byFile = index[normalize(theme.file.name.removeSuffix(".json"))]
        val file = byFile ?: index[normalize(theme.name)] ?: return null
        return MIRROR_RAW + URLEncoder.encode(file, "UTF-8").replace("+", "%20")
    }

    /** Overwrites [theme]'s file with the mirror copy at [url]. Blocking. */
    fun replaceWithMirror(theme: ThemeFile, url: String) {
        val text = Http.simpleGet(url)
        val json = JSONObject(text)
        check(!hasDiscordBackground(json)) { "The mirror copy still uses a Discord background link" }
        theme.file.writeText(text)
    }

    private const val MAX_PAGES = 10
}
