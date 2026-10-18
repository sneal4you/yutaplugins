package com.github.yutaplug.themes

import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.LongSparseArray
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.utilities.color.ColorCompat
import com.google.android.material.textfield.TextInputLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Applies a [ThemeState]'s colors with Themer's semantics, at hook points that still fire on
 * current Android:
 * - by name, only for direct `Resources.getColor(id)` calls (theme attributes through
 *   `ColorCompat.getThemedColor` and `resolveAttribute`);
 * - by value everywhere else (see [CanonicalColors]), including the [TypedArray]s and resource
 *   values Android fills natively. Themer swapped those values only later, in methods that newer
 *   Android inlines; that is why chat input, DM and channel backgrounds stopped being themed on
 *   Android 12+.
 *
 * Literal colors written in XML are never swapped, and a color the theme itself produced is never
 * swapped again, so layers do not chain.
 *
 * Hooks are installed once and read [state]; a reload only swaps the state, clears resource caches
 * and recreates the activity. A hook whose method is missing on this Android version is skipped.
 */
internal object ColorEngine {
    @Volatile
    var state: ThemeState = ThemeState.EMPTY

    @Volatile
    var isLight = false

    /** Hooks that could not be installed on this device, for the settings page. */
    val skippedHooks = ArrayList<String>()

    private val bypassing = ThreadLocal<Boolean>()
    private val bypassUsers = AtomicInteger()

    /** Runs [block] with the hooks disabled for the calling thread, to read original resource values. */
    fun <T> bypass(block: () -> T): T {
        val previous = bypassing.get()
        bypassing.set(true)
        bypassUsers.incrementAndGet()
        try {
            return block()
        } finally {
            bypassUsers.decrementAndGet()
            bypassing.set(previous)
        }
    }

    /** Cheap in the common case: the thread-local is only read while some thread is bypassing. */
    private fun bypassed() = bypassUsers.get() != 0 && bypassing.get() == true

    /** The state to apply on this thread, or null when there is nothing to do. */
    private fun active(): ThemeState? {
        val s = state
        if (!s.hasColors || bypassed()) return null
        return s
    }

    private fun isColor(type: Int) = type >= TypedValue.TYPE_FIRST_COLOR_INT && type <= TypedValue.TYPE_LAST_COLOR_INT

    private val int = Int::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!
    private val themeImpl: Class<*>? = runCatching {
        Class.forName("android.content.res.ResourcesImpl\$ThemeImpl")
    }.getOrNull()
    private val resourcesImpl: Class<*>? = runCatching {
        Class.forName(
            "android.content.res.ResourcesImpl",
        )
    }.getOrNull()

    fun install(patcher: PatcherAPI) {
        skippedHooks.clear()
        deoptimize()
        patchResourceValues(patcher)
        patchAttributes(patcher)
        patchTypedArrays(patcher)
        patchGetColor(patcher)
        patchDrawables(patcher)
        patchValueSinks(patcher)
        patchSpecials(patcher)
    }

    private fun PatcherAPI.tryPatch(description: String, hook: XC_MethodHook, find: () -> Member?) {
        try {
            val member = find() ?: throw NoSuchMethodException(description)
            patch(member, hook)
        } catch (error: Throwable) {
            skippedHooks += description
            logger.warn("Themes: skipped hook $description (${error.javaClass.simpleName}: ${error.message})")
        }
    }

    private fun Class<*>.methodsNamed(name: String) = declaredMethods.filter { it.name == name }

    // ---- 1. Resource values by id ----

    private fun patchResourceValues(patcher: PatcherAPI) {
        patcher.tryPatch(
            "AssetManager.getResourceValue",
            Hook { param ->
                if (param.result != true) return@Hook
                val s = active() ?: return@Hook
                val value = param.args[2] as TypedValue
                if (!isColor(value.type)) return@Hook
                val color = s.mapValue(value.data)
                if (color != null) value.data = color
            },
        ) {
            AssetManager::class.java.getDeclaredMethod("getResourceValue", int, int, TypedValue::class.java, bool)
        }
    }

    // ---- 2. Theme attributes ----

    private fun applyAttr(s: ThemeState, attr: Int, value: TypedValue) {
        val override = s.attr(attr)
        if (override != null && (isColor(value.type) || value.type == TypedValue.TYPE_STRING)) {
            value.type = TypedValue.TYPE_INT_COLOR_ARGB8
            value.data = override
            return
        }
        // Literal colors in styles are left alone, as Themer did; see rewrite().
        if (!isColor(value.type) || value.resourceId == 0) return
        val color = s.mapValue(value.data)
        if (color != null) value.data = color
    }

    private fun patchAttributes(patcher: PatcherAPI) {
        val hook = Hook { param ->
            if (param.result != true) return@Hook
            val s = active() ?: return@Hook
            applyAttr(s, param.args[0] as Int, param.args[1] as TypedValue)
        }
        if (Build.VERSION.SDK_INT >= 24 && themeImpl != null) {
            patcher.tryPatch("ThemeImpl.resolveAttribute", hook) {
                themeImpl.getDeclaredMethod("resolveAttribute", int, TypedValue::class.java, bool)
            }
        } else {
            patcher.tryPatch("Theme.resolveAttribute", hook) {
                Resources.Theme::class.java.getDeclaredMethod("resolveAttribute", int, TypedValue::class.java, bool)
            }
        }
        // Discord's helper; resolveAttribute may be inlined into it.
        patcher.tryPatch(
            "ColorCompat.getThemedColor",
            PreHook { param ->
                val s = active() ?: return@PreHook
                s.attr(param.args[1] as Int)?.let { param.result = it }
            },
        ) {
            ColorCompat::class.java.getDeclaredMethod("getThemedColor", android.content.Context::class.java, int)
        }
    }

    // ---- 3. TypedArrays filled by native code ----

    private val typedArrayData: Field? = runCatching {
        TypedArray::class.java.getDeclaredField("mData").apply { isAccessible = true }
    }.getOrNull()
    private val typedArrayLength: Field? = runCatching {
        TypedArray::class.java.getDeclaredField("mLength").apply { isAccessible = true }
    }.getOrNull()

    private fun styleEntries(type: Class<*>): Int? = runCatching {
        val field = type.getDeclaredField("STYLE_NUM_ENTRIES")
        field.isAccessible = true
        field.getInt(null)
    }.getOrNull()

    /** Ints per TypedArray entry: 7 since Android 10 added the source style id, 6 before. */
    private val stride: Int = styleEntries(TypedArray::class.java)
        ?: styleEntries(AssetManager::class.java)
        ?: if (Build.VERSION.SDK_INT >= 29) 7 else 6

    private const val STYLE_TYPE = 0
    private const val STYLE_DATA = 1
    private const val STYLE_RESOURCE_ID = 3

    private fun rewrite(array: Any?, attrs: IntArray?) {
        if (array !is TypedArray) return
        val s = active() ?: return
        val data = typedArrayData?.get(array) as? IntArray ?: return
        val length = typedArrayLength?.getInt(array) ?: (data.size / stride)
        for (i in 0 until length) {
            val base = i * stride
            if (base + STYLE_RESOURCE_ID >= data.size) break
            val type = data[base + STYLE_TYPE]
            val override = attrs?.getOrNull(i)?.let { s.attr(it) }
            if (override != null && (isColor(type) || type == TypedValue.TYPE_STRING)) {
                data[base + STYLE_TYPE] = TypedValue.TYPE_INT_COLOR_ARGB8
                data[base + STYLE_DATA] = override
                continue
            }
            if (!isColor(type)) continue
            // Literal colors written in XML (resource id 0) are not swapped by value: drawables use them
            // as masks for their tint, e.g. the log-out icon's #4f545c fill, which a theme making
            // primary_500 transparent would otherwise erase. Themer never reached these either.
            val resourceId = data[base + STYLE_RESOURCE_ID]
            if (resourceId == 0) continue
            val color = s.mapValue(data[base + STYLE_DATA])
            if (color != null) data[base + STYLE_DATA] = color
        }
    }

    /** The `attrs` argument: the first int[] for obtain calls, the second for resolveAttributes. */
    private fun attrsArg(args: Array<Any?>, second: Boolean): IntArray? {
        val arrays = args.filterIsInstance<IntArray>()
        return if (second) arrays.getOrNull(1) else arrays.firstOrNull()
    }

    private fun patchTypedArrays(patcher: PatcherAPI) {
        if (typedArrayData == null) {
            skippedHooks += "TypedArray.mData"
            return
        }
        val obtain = Hook { param -> rewrite(param.result, attrsArg(param.args, false)) }
        val resolve = Hook { param -> rewrite(param.result, attrsArg(param.args, true)) }
        if (Build.VERSION.SDK_INT >= 24 && themeImpl != null) {
            patcher.tryPatch("ThemeImpl.obtainStyledAttributes", obtain) {
                themeImpl.getDeclaredMethod(
                    "obtainStyledAttributes",
                    Resources.Theme::class.java,
                    AttributeSet::class.java,
                    IntArray::class.java,
                    int,
                    int,
                )
            }
            patcher.tryPatch("ThemeImpl.resolveAttributes", resolve) {
                themeImpl.getDeclaredMethod(
                    "resolveAttributes",
                    Resources.Theme::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                )
            }
        } else {
            Resources.Theme::class.java.methodsNamed("obtainStyledAttributes").forEach { method ->
                patcher.tryPatch("Theme.obtainStyledAttributes(${method.parameterTypes.size})", obtain) { method }
            }
            patcher.tryPatch("Theme.resolveAttributes", resolve) {
                Resources.Theme::class.java.getDeclaredMethod(
                    "resolveAttributes",
                    IntArray::class.java,
                    IntArray::class.java,
                )
            }
        }
        patcher.tryPatch("Resources.obtainAttributes", obtain) {
            Resources::class.java.getDeclaredMethod("obtainAttributes", AttributeSet::class.java, IntArray::class.java)
        }
    }

    // ---- 4. Colors by name: direct Resources.getColor(id) calls, as in Themer ----

    private fun patchGetColor(patcher: PatcherAPI) {
        val hook = Hook { param ->
            val s = active() ?: return@Hook
            s.color(param.args[0] as Int)?.let { param.result = it }
        }
        Resources::class.java.methodsNamed("getColor").forEach { method ->
            patcher.tryPatch("Resources.getColor(${method.parameterTypes.size})", hook) { method }
        }
    }

    // ---- 5. Drawables: color values and tints ----

    private fun patchDrawables(patcher: PatcherAPI) {
        val loadDrawable: Method? = runCatching {
            when {
                Build.VERSION.SDK_INT >= 26 -> resourcesImpl!!.getDeclaredMethod(
                    "loadDrawable",
                    Resources::class.java,
                    TypedValue::class.java,
                    int,
                    int,
                    Resources.Theme::class.java,
                )

                Build.VERSION.SDK_INT >= 24 -> resourcesImpl!!.getDeclaredMethod(
                    "loadDrawable",
                    Resources::class.java,
                    TypedValue::class.java,
                    int,
                    Resources.Theme::class.java,
                    bool,
                )

                else -> Resources::class.java.getDeclaredMethod(
                    "loadDrawable",
                    TypedValue::class.java,
                    int,
                    Resources.Theme::class.java,
                )
            }
        }.getOrNull()
        val valueIndex = if (Build.VERSION.SDK_INT >= 24) 1 else 0
        patcher.tryPatch(
            "loadDrawable (colors)",
            PreHook { param ->
                val s = active() ?: return@PreHook
                val value = param.args[valueIndex] as? TypedValue ?: return@PreHook
                if (!isColor(value.type)) return@PreHook
                val color = s.mapValue(value.data)
                if (color != null) value.data = color
            },
        ) { loadDrawable }
        patcher.tryPatch(
            "loadDrawable (tints)",
            Hook { param ->
                val s = state
                if (s.tintById.size() == 0 || bypassed()) return@Hook
                val tint = s.tint(param.args[valueIndex + 1] as Int) ?: return@Hook
                val drawable = param.result as? Drawable ?: return@Hook
                param.result = drawable.mutate().apply { setTint(tint) }
            },
        ) { loadDrawable }
    }

    // ---- 6. Colors without an id (Themer's by-value swap) ----

    private fun patchValueSinks(patcher: PatcherAPI) {
        patcher.tryPatch(
            "ColorDrawable.setColor",
            PreHook { param ->
                val s = active()
                val color = param.args[0] as Int
                s?.mapValue(color)?.let {
                    param.args[0] = it
                    return@PreHook
                }
                val blocked = state.blockedBackground ?: return@PreHook
                if (bypassed()) return@PreHook
                if (color == (if (isLight) BLOCKED_COLOR_LIGHT else BLOCKED_COLOR_DARK)) param.args[0] = blocked
            },
        ) { ColorDrawable::class.java.getDeclaredMethod("setColor", int) }
        patcher.tryPatch(
            "GradientDrawable.setColor",
            PreHook { param ->
                val s = active() ?: return@PreHook
                s.mapValue(param.args[0] as Int)?.let { param.args[0] = it }
            },
        ) { GradientDrawable::class.java.getDeclaredMethod("setColor", int) }
        patcher.tryPatch(
            "ColorStateList.getColorForState",
            Hook { param ->
                val s = active() ?: return@Hook
                s.mapValue(param.result as Int)?.let { param.result = it }
            },
        ) { ColorStateList::class.java.getDeclaredMethod("getColorForState", IntArray::class.java, int) }
        // getDefaultColor is not hooked: vector drawables read their literal path colors through it.
    }

    // ---- 7. Themer's special keys ----

    private fun patchSpecials(patcher: PatcherAPI) {
        patcher.tryPatch(
            "ColorCompat.setStatusBarColor",
            PreHook { param ->
                state.statusBar?.let { param.args[1] = it }
            },
        ) { ColorCompat::class.java.getDeclaredMethod("setStatusBarColor", Window::class.java, int, bool) }
        patcher.tryPatch(
            "TextInputLayout.calculateBoxBackgroundColor",
            Hook { param ->
                state.inputBackground?.let { param.result = it }
            },
        ) { TextInputLayout::class.java.getDeclaredMethod("calculateBoxBackgroundColor") }
    }

    /**
     * ART inlines small framework methods into their callers, which then skip our hooks. Running
     * these callers interpreted again restores the hooked paths (the same set NewThemes relies on).
     */
    private fun deoptimize() {
        val targets = ArrayList<Member>()
        listOf(
            // Callers of ColorStateList.getColorForState / ColorDrawable.setColor.
            TextView::class.java to "updateTextColors",
            RippleDrawable::class.java to "updateRipplePaint",
            Drawable::class.java to "updateBlendModeFilter",
            GradientDrawable::class.java to "updateLocalState",
            View::class.java to "setBackgroundColor",
            // Callers of the hooked resource, attribute and TypedArray entry points.
            Drawable::class.java to "obtainAttributes",
            Resources::class.java to "obtainAttributes",
            Resources::class.java to "getDrawableForDensity",
            Resources::class.java to "getColor",
            Resources::class.java to "getColorStateList",
            Resources.Theme::class.java to "obtainStyledAttributes",
            Resources.Theme::class.java to "resolveAttribute",
            TypedArray::class.java to (if (Build.VERSION.SDK_INT >= 26) "getDrawableForDensity" else "getDrawable"),
            TypedArray::class.java to "getColorStateList",
            ColorCompat::class.java to "getThemedColor",
        ).forEach { (type, name) -> targets += type.methodsNamed(name) }
        resourcesImpl?.let { targets += it.methodsNamed("getValue") }
        // Callers of the by-name Resources.getColor hook.
        targets += android.content.Context::class.java.methodsNamed("getColor")
        targets += androidx.core.content.ContextCompat::class.java.methodsNamed("getColor")
        targets += ColorCompat::class.java.methodsNamed("getColor")
        targets.distinct().forEach { member -> runCatching { XposedBridge.deoptimizeMethod(member) } }
    }

    /** Drops cached drawables and color lists so the next lookup sees the current state. */
    fun clearCaches(resources: Resources) {
        fun field(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val candidate = type
                val result = runCatching { candidate.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
                if (result.isSuccess) return result.getOrNull()
                type = candidate.superclass
            }
            return null
        }

        fun clear(value: Any?) {
            when (value) {
                is MutableMap<*, *> -> value.clear()
                is LongSparseArray<*> -> value.clear()
            }
        }
        val impl = field(resources, "mResourcesImpl") ?: resources
        arrayOf("mDrawableCache", "mColorDrawableCache", "mColorStateListCache", "mComplexColorCache").forEach { name ->
            val cache = field(impl, name) ?: return@forEach
            synchronized(cache) {
                clear(cache)
                arrayOf("mThemedEntries", "mUnthemedEntries", "mNullThemedEntries").forEach { clear(field(cache, it)) }
            }
        }
        arrayOf(AppCompatResources::class.java, ResourcesCompat::class.java).forEach { type ->
            runCatching {
                val lock = type.getDeclaredField("sColorStateCacheLock").apply { isAccessible = true }.get(null)!!
                synchronized(lock) {
                    clear(type.getDeclaredField("sColorStateCaches").apply { isAccessible = true }.get(null))
                }
            }
        }
    }
}
