package com.github.yutaplug.themes

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.util.SparseArray
import android.util.SparseIntArray
import java.io.File
import java.util.Arrays

/**
 * Everything the hooks need, resolved to resource ids. Immutable once built: a reload builds a new
 * state and swaps it in, so hooks on other threads always read a consistent snapshot.
 */
internal class ThemeState(
    /** Color resource id to color. Includes colors reached through overridden theme attributes. */
    val colorById: SparseIntArray,
    /** Color state list (selector) resource id to the color replacing its default state. */
    val selectorById: SparseIntArray,
    /** Theme attribute id to color. */
    val attrById: SparseIntArray,
    /** Themer's by-value swap: original palette value to new color, for colors without a resource id. */
    val valueMap: SparseIntArray,
    /** Every color this state produces, sorted; a value fallback never remaps one of these again. */
    private val outputs: IntArray,
    val tintById: SparseIntArray,
    val fontById: SparseArray<Typeface>,
    val defaultFont: Typeface?,
    val rawById: SparseArray<File>,
    val statusBar: Int?,
    val inputBackground: Int?,
    val blockedBackground: Int?,
    val wallpaper: Bitmap?,
    val animatedWallpaper: Uri?,
    val overlayAlpha: Int,
    val warnings: List<String>,
    /** Files that still have to be downloaded before this state is complete. */
    val pendingDownloads: List<String>,
) {
    val isEmpty =
        colorById.size() == 0 &&
            selectorById.size() == 0 &&
            attrById.size() == 0 &&
            valueMap.size() == 0 &&
            tintById.size() == 0 &&
            fontById.size() == 0 &&
            defaultFont == null &&
            rawById.size() == 0 &&
            statusBar == null &&
            inputBackground == null &&
            blockedBackground == null &&
            !hasBackground

    val hasBackground get() = wallpaper != null || animatedWallpaper != null

    val hasColors = colorById.size() != 0 || attrById.size() != 0 || valueMap.size() != 0 || selectorById.size() != 0

    fun color(id: Int): Int? = colorById.find(id)

    fun attr(id: Int): Int? = attrById.find(id)

    fun tint(id: Int): Int? = tintById.find(id)

    fun selector(id: Int): Int? = selectorById.find(id)

    /** The by-value replacement for [color], or null. Colors this theme already produced are left alone. */
    fun mapValue(color: Int): Int? {
        if (valueMap.size() == 0) return null
        val mapped = valueMap.find(color) ?: return null
        return if (isOutput(color)) null else mapped
    }

    fun isOutput(color: Int) = Arrays.binarySearch(outputs, color) >= 0

    fun font(id: Int): Typeface? = fontById.get(id) ?: if (id != 0) defaultFont else null

    companion object {
        val EMPTY = ThemeState(
            SparseIntArray(),
            SparseIntArray(),
            SparseIntArray(),
            SparseIntArray(),
            IntArray(0),
            SparseIntArray(),
            SparseArray(),
            null,
            SparseArray(),
            null,
            null,
            null,
            null,
            null,
            0,
            emptyList(),
            emptyList(),
        )

        /** Values shared by unrelated UI (button labels, scrims), never swapped by value alone. */
        val UNLINKED = intArrayOf(Color.WHITE, Color.BLACK, Color.TRANSPARENT)
    }
}

internal fun SparseIntArray.find(key: Int): Int? {
    val index = indexOfKey(key)
    return if (index < 0) null else valueAt(index)
}
