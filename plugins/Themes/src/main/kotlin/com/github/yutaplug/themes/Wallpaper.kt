package com.github.yutaplug.themes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.aliucord.Utils
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bakes a theme's background: downscaled, darkened by `overlay_alpha` and blurred once, then cached
 * as a JPEG so later starts only decode it. The blur is plain Kotlin; Themer's RenderScript blur is
 * deprecated and unreliable on current Android.
 */
internal object Wallpaper {
    private const val MAX_SIDE = 1920

    fun prepare(source: File, url: String, overlayAlpha: Int, blurRadius: Double): Bitmap {
        val key = "$url|$overlayAlpha|$blurRadius|${source.length()}|${source.lastModified()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val baked = File(
            File(Utils.appContext.cacheDir, "Themes-Cache").apply { mkdirs() },
            "wallpaper-" + digest.joinToString("") { String.format("%02x", it) } + ".jpg",
        )
        if (baked.isFile) BitmapFactory.decodeFile(baked.path)?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Not an image")
        val image = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
        }
        if (overlayAlpha > 0) Canvas(image).drawColor(Color.argb(overlayAlpha, 0, 0, 0))
        if (blurRadius > 0) blur(image, blurRadius / sample)
        runCatching { baked.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        return image
    }

    /** Three box blurs approximating the Gaussian RenderScript used for the same radius. */
    private fun blur(image: Bitmap, radius: Double) {
        val sigma = 0.4 * radius + 0.6
        val box = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt()
        if (box < 1) return
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            boxBlur(pixels, scratch, width, height, box, true)
            boxBlur(scratch, pixels, width, height, box, false)
        }
        image.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxBlur(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            val step = if (horizontal) 1 else width

            fun at(i: Int) = source[start + i.coerceIn(0, length - 1) * step]
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = at(i)
                r += (p shr 16) and 0xff
                g += (p shr 8) and 0xff
                b += p and 0xff
            }
            for (i in 0 until length) {
                target[start + i * step] =
                    (0xff shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = at(i - radius)
                val inc = at(i + radius + 1)
                r += ((inc shr 16) and 0xff) - ((out shr 16) and 0xff)
                g += ((inc shr 8) and 0xff) - ((out shr 8) and 0xff)
                b += (inc and 0xff) - (out and 0xff)
            }
        }
    }
}
