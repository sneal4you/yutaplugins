package com.github.yutaplug.themes

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.PreHook

/** Theme fonts and sounds, both replaced by resource id. */
internal object FontsAndSounds {
    private val int = Int::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!

    fun install(patcher: PatcherAPI) {
        // Every AndroidX font lookup (layouts, TextViews, FontUtils) ends here.
        runCatching {
            patcher.patch(
                ResourcesCompat::class.java.getDeclaredMethod(
                    "loadFont",
                    Context::class.java,
                    Resources::class.java,
                    TypedValue::class.java,
                    int,
                    int,
                    ResourcesCompat.FontCallback::class.java,
                    Handler::class.java,
                    bool,
                    bool,
                ),
                PreHook { param ->
                    val font = try {
                        ColorEngine.state.font(param.args[3] as Int)
                    } catch (error: Throwable) {
                        null
                    } ?: return@PreHook
                    param.result = font
                    // A failing callback must not take the app down; the font is returned either way.
                    runCatching {
                        (param.args[5] as ResourcesCompat.FontCallback?)?.callbackSuccessAsync(
                            font,
                            param.args[6] as Handler?,
                        )
                    }
                },
            )
        }.onFailure {
            ColorEngine.skippedHooks += "ResourcesCompat.loadFont"
            logger.warn("Themes: font hook unavailable (${it.message})")
        }
        // Framework font lookups (android:fontFamily without AppCompat) on Android 8+.
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val impl = Class.forName("android.content.res.ResourcesImpl")
                patcher.patch(
                    impl.getDeclaredMethod("loadFont", Resources::class.java, TypedValue::class.java, int),
                    PreHook { param -> ColorEngine.state.font(param.args[2] as Int)?.let { param.result = it } },
                )
            }
        }

        runCatching {
            patcher.patch(
                Resources::class.java.getDeclaredMethod("openRawResourceFd", int),
                PreHook { param ->
                    val file = ColorEngine.state.rawById.get(param.args[0] as Int) ?: return@PreHook
                    try {
                        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        param.result = AssetFileDescriptor(fd, 0, file.length())
                    } catch (error: Throwable) {
                        logger.error("Failed to open theme sound ${file.name}", error)
                    }
                },
            )
        }.onFailure {
            ColorEngine.skippedHooks += "Resources.openRawResourceFd"
            logger.warn("Themes: sound hook unavailable (${it.message})")
        }
    }
}
