package com.github.yutaplug.themes

import android.content.Context
import android.os.Build
import com.lytefast.flexinput.R
import java.io.File

/**
 * Themer's by-value lookup, reproduced exactly: every Discord color value maps to one name, the
 * last color resource (in declaration order) that has that value. Themer swaps a color by value
 * only when the theme sets that one name, so unrelated colors that merely share a value keep it.
 *
 * Reading every color takes a moment, so the table is cached per installed Discord build.
 */
internal object CanonicalColors {
    @Volatile
    private var cached: Map<Int, String>? = null

    /** Value to the one name Themer associates with it. Must run with [ColorEngine] bypassed. */
    fun get(context: Context): Map<Int, String> {
        cached?.let { return it }
        val file = cacheFile(context)
        val table = runCatching { read(file) }.getOrNull() ?: build(context).also { table ->
            runCatching { file.writeText(table.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        }
        cached = table
        return table
    }

    private fun build(context: Context): Map<Int, String> {
        val table = LinkedHashMap<Int, String>()
        R.c::class.java.declaredFields.forEach { field ->
            val color = runCatching { context.getColor(field.getInt(null)) }.getOrNull() ?: return@forEach
            if (color != 0) table[color] = field.name
        }
        return table
    }

    private fun read(file: File): Map<Int, String>? {
        if (!file.isFile) return null
        val table = HashMap<Int, String>()
        file.readLines().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) table[line.substring(0, tab).toInt()] = line.substring(tab + 1)
        }
        return table.takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun cacheFile(context: Context): File {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val dir = File(context.cacheDir, "Themes-Cache").apply { mkdirs() }
        return File(dir, "colors-$version-${info.lastUpdateTime}.tsv")
    }
}
