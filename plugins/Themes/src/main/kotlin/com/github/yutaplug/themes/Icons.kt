package com.github.yutaplug.themes

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.appcompat.graphics.drawable.DrawableWrapper
import androidx.appcompat.widget.ResourceManagerInternal
import androidx.core.content.res.ResourcesCompat
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.io.File

/**
 * One value of a theme's `icons` section. The [source] is an image link, or the name of another
 * Discord drawable to show instead. Written as the plain string, or as
 * `{"url": "...", "keep_colors": true}` / `{"drawable": "...", "keep_colors": true}` for one that
 * must not be tinted.
 */
internal class IconEntry(source: String, val keepColors: Boolean) {
    val source = source.trim().removePrefix(DRAWABLE_PREFIX)

    /** Whether [source] names a Discord drawable rather than an image link. */
    val isDrawable get() = "://" !in source

    /** The JSON value for this entry, in the short form when possible. */
    fun toJson(): Any = if (keepColors) {
        JSONObject().put(if (isDrawable) "drawable" else "url", source).put(KEEP_COLORS, true)
    } else {
        source
    }

    companion object {
        const val KEEP_COLORS = "keep_colors"
        private const val DRAWABLE_PREFIX = "@drawable/"

        // Not isNotBlank(): it iterates an IntRange, which crashes under Discord's renamed stdlib.
        private fun String.hasText() = trim().isNotEmpty()

        fun parse(value: Any?): IconEntry? = when (value) {
            is String -> {
                value.takeIf { it.hasText() }?.let { IconEntry(it, false) }
            }

            is JSONObject -> {
                (value.optString("drawable").takeIf { it.hasText() } ?: value.optString("url"))
                    .takeIf { it.hasText() }
                    ?.let { IconEntry(it, value.optBoolean(KEEP_COLORS, false)) }
            }

            else -> {
                null
            }
        }
    }
}

/**
 * One `icons` entry of the theme: what replaces a Discord drawable. Either the image [file], or the
 * Discord drawable [drawableId], always loaded as Discord ships it (never itself replaced).
 */
internal class ThemeIcon(
    val name: String,
    val file: File?,
    val drawableId: Int,
    val keepColors: Boolean,
) {
    @Volatile
    private var bitmap: Bitmap? = null

    @Volatile
    private var failed = false

    /**
     * The decoded image, at most twice the [target] size of the drawable it replaces (so it stays
     * sharp when a view scales it up) and never more than [MAX_SIZE]. Decoded once, on first use.
     */
    fun bitmap(target: Int): Bitmap? {
        bitmap?.let { return it }
        if (failed) return null
        synchronized(this) {
            bitmap?.let { return it }
            if (failed) return null
            val file = file ?: return null
            val limit = if (target > 0) (target * 2).coerceIn(MIN_SIZE, MAX_SIZE) else MAX_SIZE
            val decoded = runCatching { decode(file, limit) }
                .onFailure { logger.error("Themes: failed to decode icon $name", it) }
                .getOrNull()
            if (decoded == null) failed = true else bitmap = decoded
            return decoded
        }
    }

    companion object {
        private const val MIN_SIZE = 96
        private const val MAX_SIZE = 1024

        /** Whether [file] is an image Android can decode (PNG, JPEG, WebP, GIF, BMP). */
        fun isImage(file: File): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }

        private fun decode(file: File, limit: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= limit) return bitmap
            val scale = limit.toFloat() / longest
            return Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt()),
                true,
            )
        }
    }
}

/**
 * Draws a theme icon in place of a Discord drawable. It keeps the original's intrinsic size, so
 * compound drawables and wrap_content views lay out as before, and it honors tints and color
 * filters like Discord's vector icons do, unless the theme asked to keep the image's own colors.
 */
internal class IconDrawable private constructor(private var icon: IconState) : Drawable() {
    internal class IconState(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val keepColors: Boolean,
    ) : ConstantState() {
        var tint: ColorStateList? = null
        var tintMode: PorterDuff.Mode = PorterDuff.Mode.SRC_IN
        var alpha = 0xff

        fun copy() = IconState(bitmap, width, height, keepColors).also {
            it.tint = tint
            it.tintMode = tintMode
            it.alpha = alpha
        }

        override fun newDrawable() = IconDrawable(this)

        override fun newDrawable(res: Resources?) = IconDrawable(this)

        override fun getChangingConfigurations() = 0
    }

    constructor(bitmap: Bitmap, width: Int, height: Int, keepColors: Boolean) :
        this(IconState(bitmap, width, height, keepColors))

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private var filter: ColorFilter? = null
    private var tintFilter: ColorFilter? = null
    private var tintColor: Int? = null
    private var mutated = false

    init {
        updateTint(state)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bitmap = icon.bitmap
        // Fit inside the bounds without stretching, like an ImageView's fitCenter.
        val scale = minOf(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        target.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        paint.alpha = icon.alpha
        paint.colorFilter = if (icon.keepColors) null else filter ?: tintFilter
        canvas.drawBitmap(bitmap, null, target, paint)
    }

    override fun getIntrinsicWidth() = icon.width

    override fun getIntrinsicHeight() = icon.height

    override fun setAlpha(alpha: Int) {
        if (icon.alpha == alpha) return
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = icon.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    override fun getColorFilter() = filter

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        icon.tint = tint
        if (updateTint(state)) invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        icon.tintMode = tintMode ?: PorterDuff.Mode.SRC_IN
        tintColor = null
        updateTint(state)
        invalidateSelf()
    }

    override fun isStateful() = icon.tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateTint(state)

    /** Recomputes the tint filter for [state]; returns whether it changed. */
    private fun updateTint(state: IntArray): Boolean {
        val tint = icon.tint
        val color = tint?.getColorForState(state, tint.defaultColor)
        if (color == tintColor && (color == null) == (tintFilter == null)) return false
        tintColor = color
        tintFilter = color?.let { PorterDuffColorFilter(it, icon.tintMode) }
        return true
    }

    override fun getConstantState(): ConstantState = icon

    override fun mutate(): Drawable {
        if (!mutated && super.mutate() === this) {
            icon = icon.copy()
            mutated = true
        }
        return this
    }
}

/**
 * Shows another Discord drawable in place of a replaced one, at the replaced one's size, so rows and
 * bars keep their layout. Tints and color filters reach the shown drawable unless the theme asked
 * to keep its colors.
 */
internal class AliasDrawable(
    drawable: Drawable,
    private val width: Int,
    private val height: Int,
    private val keepColors: Boolean,
) : DrawableWrapper(drawable) {
    private var mutated = false

    override fun getIntrinsicWidth() = width

    override fun getIntrinsicHeight() = height

    /** Fits the shown drawable inside the bounds without stretching, like an ImageView's fitCenter. */
    override fun onBoundsChange(bounds: Rect) {
        val inner = wrappedDrawable
        val w = inner.intrinsicWidth
        val h = inner.intrinsicHeight
        if (w <= 0 || h <= 0 || bounds.isEmpty) {
            inner.bounds = bounds
            return
        }
        val scale = minOf(bounds.width().toFloat() / w, bounds.height().toFloat() / h)
        val dw = (w * scale).toInt()
        val dh = (h * scale).toInt()
        val left = bounds.left + (bounds.width() - dw) / 2
        val top = bounds.top + (bounds.height() - dh) / 2
        inner.setBounds(left, top, left + dw, top + dh)
    }

    override fun setTint(tint: Int) {
        if (!keepColors) super.setTint(tint)
    }

    override fun setTintList(tint: ColorStateList?) {
        if (!keepColors) super.setTintList(tint)
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        if (!keepColors) super.setTintMode(tintMode)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (!keepColors) super.setColorFilter(colorFilter)
    }

    override fun mutate(): Drawable {
        if (!mutated) {
            setWrappedDrawable(wrappedDrawable.mutate())
            mutated = true
        }
        return this
    }

    override fun getConstantState(): ConstantState? =
        wrappedDrawable.constantState?.let { State(it, width, height, keepColors) }

    private class State(
        private val inner: ConstantState,
        private val width: Int,
        private val height: Int,
        private val keepColors: Boolean,
    ) : ConstantState() {
        override fun newDrawable() = AliasDrawable(inner.newDrawable(), width, height, keepColors)

        override fun newDrawable(res: Resources?) = AliasDrawable(inner.newDrawable(res), width, height, keepColors)

        override fun getChangingConfigurations() = inner.changingConfigurations
    }
}

/** Replaces Discord drawables with the images or other drawables in the enabled themes' `icons` section. */
internal object Icons {
    /** Set while a replacement drawable loads, so it comes out as Discord ships it (no chains or loops). */
    private val loadingReplacement = ThreadLocal<Boolean>()

    fun install(patcher: PatcherAPI) {
        ColorEngine.prepare()
        ColorEngine.patchLoadedDrawables(patcher, "icons") { param, id -> replace(param, id) }
        // Before Android 7, AppCompat inflates vector icons itself (app:srcCompat, AppCompatResources)
        // instead of going through Resources.loadDrawable.
        if (Build.VERSION.SDK_INT < 24) {
            runCatching {
                patcher.patch(
                    ResourceManagerInternal::class.java.getDeclaredMethod(
                        "loadDrawableFromDelegates",
                        Context::class.java,
                        Int::class.javaPrimitiveType,
                    ),
                    Hook { param -> replace(param, param.args[1] as Int) },
                )
            }.onFailure {
                ColorEngine.skippedHooks += "ResourceManagerInternal.loadDrawableFromDelegates (icons)"
                logger.warn("Themes: AppCompat icon hook unavailable (${it.message})")
            }
        }
    }

    private fun replace(param: XC_MethodHook.MethodHookParam, id: Int) {
        val s = ColorEngine.state
        if (s.iconById.size() == 0 || ColorEngine.bypassed() || loadingReplacement.get() == true) return
        val icon = s.iconById.get(id) ?: return
        val original = param.result as? Drawable ?: return
        if (original is IconDrawable || original is AliasDrawable) return
        val replacement = if (icon.drawableId != 0) alias(param, icon, original) else image(icon, original)
        param.result = replacement?.apply {
            // drawable_tints of the same theme still apply to the replacement.
            s.tint(id)?.let { mutate().setTint(it) }
        } ?: return
    }

    private fun image(icon: ThemeIcon, original: Drawable): Drawable? {
        val bitmap = icon.bitmap(maxOf(original.intrinsicWidth, original.intrinsicHeight)) ?: return null
        // Shapes and colors have no size of their own; use the image's, as a plain bitmap would.
        val width = original.intrinsicWidth.takeIf { it > 0 } ?: bitmap.width
        val height = original.intrinsicHeight.takeIf { it > 0 } ?: bitmap.height
        return IconDrawable(bitmap, width, height, icon.keepColors)
    }

    /** Loads the other drawable with the same resources and theme as the one it replaces. */
    private fun alias(param: XC_MethodHook.MethodHookParam, icon: ThemeIcon, original: Drawable): Drawable? {
        val context = param.args.firstOrNull { it is Context } as? Context
        val resources = param.args.firstOrNull { it is Resources } as? Resources
            ?: param.thisObject as? Resources
            ?: context?.resources
            ?: return null
        val theme = param.args.firstOrNull { it is Resources.Theme } as? Resources.Theme ?: context?.theme
        loadingReplacement.set(true)
        val drawable = try {
            ResourcesCompat.getDrawable(resources, icon.drawableId, theme)
        } catch (error: Throwable) {
            logger.error("Themes: failed to load the replacement for icon ${icon.name}", error)
            null
        } finally {
            loadingReplacement.set(false)
        } ?: return null
        val width = original.intrinsicWidth.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val height = original.intrinsicHeight.takeIf { it > 0 } ?: drawable.intrinsicHeight
        return AliasDrawable(drawable, width, height, icon.keepColors)
    }
}
