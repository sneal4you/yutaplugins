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
 * Applies a [ThemeState]'s colors.
 *
 * Colors are matched by resource id wherever the framework still knows it, at the lowest Java layer
 * every lookup passes through:
 * - [AssetManager] `getResourceValue`: `Resources.getColor`, `getValue` and color drawables by id;
 * - theme attribute resolution: `resolveAttribute`, `ColorCompat.getThemedColor`;
 * - every [TypedArray] the framework fills natively (styles, layouts, drawable and color XML). These
 *   carry `?attr/` colors, which Themer never saw; they are why chat input, DM and channel
 *   backgrounds stopped being themed on Android 12+.
 *
 * Themer's by-value swap stays as a fallback for colors that arrive without an id (hardcoded
 * colors, literals in styles). It never remaps a color the theme itself produced, so layers do not chain.
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
        patchColorStateLists(patcher)
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
                val color = s.color(param.args[0] as Int) ?: s.color(value.resourceId) ?: s.mapValue(value.data)
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
        if (!isColor(value.type)) return
        val color = (if (value.resourceId != 0) s.color(value.resourceId) else null) ?: s.mapValue(value.data)
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
            val resourceId = data[base + STYLE_RESOURCE_ID]
            val color = (if (resourceId != 0) s.color(resourceId) else null) ?: s.mapValue(data[base + STYLE_DATA])
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

    // ---- 4. Color state lists (selectors) by id ----

    private val getStates: Method? = runCatching {
        ColorStateList::class.java.getDeclaredMethod("getStates").apply { isAccessible = true }
    }.getOrNull()
    private val getColors: Method? = runCatching {
        ColorStateList::class.java.getDeclaredMethod("getColors").apply { isAccessible = true }
    }.getOrNull()

    /** Recolors the entries sharing the list's default color, keeping each entry's own alpha. */
    private fun recolor(list: ColorStateList, color: Int): ColorStateList {
        val default = bypass { list.defaultColor }

        @Suppress("UNCHECKED_CAST")
        val states = getStates?.invoke(list) as? Array<IntArray>
        val colors = getColors?.invoke(list) as? IntArray
        if (states == null || colors == null || states.size != colors.size) return ColorStateList.valueOf(color)
        val rgb = default and 0xffffff
        val recolored = IntArray(colors.size) { i ->
            val c = colors[i]
            if (c and 0xffffff != rgb) {
                c
            } else {
                val alpha = ((c ushr 24) * (color ushr 24) + 127) / 255
                (alpha shl 24) or (color and 0xffffff)
            }
        }
        return ColorStateList(states, recolored)
    }

    private fun patchColorStateLists(patcher: PatcherAPI) {
        val hook = Hook { param ->
            val s = active() ?: return@Hook
            val list = param.result as? ColorStateList ?: return@Hook
            val id = param.args.firstOrNull { it is Int } as? Int ?: return@Hook
            val color = s.selector(id) ?: return@Hook
            param.result = recolor(list, color)
        }
        val owner = if (Build.VERSION.SDK_INT >= 24 && resourcesImpl != null) resourcesImpl else Resources::class.java
        owner.methodsNamed("loadColorStateList").forEach { method ->
            patcher.tryPatch("${owner.simpleName}.loadColorStateList", hook) { method }
        }
        if (Build.VERSION.SDK_INT >= 24 && resourcesImpl != null) {
            resourcesImpl.methodsNamed("loadComplexColor").forEach { method ->
                patcher.tryPatch("ResourcesImpl.loadComplexColor", hook) { method }
            }
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
                val color = (if (value.resourceId != 0) s.color(value.resourceId) else null) ?: s.mapValue(value.data)
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
        patcher.tryPatch(
            "ColorStateList.getDefaultColor",
            Hook { param ->
                val s = active() ?: return@Hook
                s.mapValue(param.result as Int)?.let { param.result = it }
            },
        ) { ColorStateList::class.java.getDeclaredMethod("getDefaultColor") }
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
