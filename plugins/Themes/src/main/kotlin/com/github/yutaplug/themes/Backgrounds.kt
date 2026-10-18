/*
 * Based on Ven's Aliucord Plugins (Themer)
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for the Themes plugin.
 */

package com.github.yutaplug.themes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.facebook.drawee.view.SimpleDraweeView
import kotlin.math.max

/** Shows the theme's background where the transparency mode allows it. */
internal object Backgrounds {
    private val backgroundId = View.generateViewId()
    private val overlayId = View.generateViewId()
    private const val PAGE_SCRIM = 100

    private val hasBackground get() = ColorEngine.state.hasBackground

    fun install(patcher: PatcherAPI) {
        val chatId = Utils.getResId("panel_center", "id")
        val chatBgId = Utils.getResId("widget_home_panel_center_chat", "id")
        val rootId = Utils.getResId("action_bar_root", "id")

        patcher.patch(
            AppFragment::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { param ->
                if (!hasBackground) return@Hook
                val mode = ThemeStore.transparencyMode
                if (mode == TransparencyMode.NONE) return@Hook
                try {
                    val view = param.args[0] as View
                    val type = param.thisObject.javaClass
                    if (mode == TransparencyMode.FULL) {
                        decorateFull(view, type, rootId)
                    } else {
                        decoratePanel(view, type, mode, chatId, chatBgId)
                    }
                } catch (error: Throwable) {
                    logger.error("Failed to apply theme background", error)
                }
            },
        )

        // Bottom sheets keep an opaque container in full transparency.
        patcher.patch(
            AppBottomSheet::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java),
            Hook { param ->
                if (hasBackground && ThemeStore.transparencyMode == TransparencyMode.FULL) {
                    ((param.args[0] as View).parent as? View)?.background = null
                }
            },
        )

        // Spoiler overlays must stay opaque, or the hidden media shows through.
        val spoilerFix = { idName: String ->
            val id = Utils.getResId(idName, "id")
            PreHook { param ->
                if (!hasBackground || ThemeStore.transparencyMode != TransparencyMode.FULL) return@PreHook
                val holder = param.thisObject as? RecyclerView.ViewHolder ?: return@PreHook
                holder.itemView.findViewById<View?>(id)?.let { spoiler ->
                    val color = (spoiler.background as? ColorDrawable)?.color ?: return@let
                    spoiler.setBackgroundColor(color or (0xff shl 24))
                }
            }
        }
        runCatching {
            patcher.patch(
                WidgetChatListAdapterItemAttachment::class.java.getDeclaredMethod(
                    "configureUI",
                    WidgetChatListAdapterItemAttachment.Model::class.java,
                ),
                spoilerFix("chat_list_item_attachment_spoiler"),
            )
            patcher.patch(
                WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
                    "configureUI",
                    WidgetChatListAdapterItemEmbed.Model::class.java,
                ),
                spoilerFix("chat_list_item_embed_spoiler"),
            )
        }.onFailure { logger.warn("Themes: spoiler background fix unavailable (${it.message})") }
    }

    private fun decoratePanel(start: View, type: Class<*>, mode: TransparencyMode, chatId: Int, chatBgId: Int) {
        val name = type.simpleName
        if (name == "WidgetChatList") {
            var view = start
            while (view.id != chatId) {
                view = view.parent as? View ?: return
                if (view.id == chatBgId) view.background = null
            }
            setBackground(view)
        } else if (
            mode == TransparencyMode.CHAT_SETTINGS &&
            (name.lowercase().contains("settings") || SettingsPage::class.java.isAssignableFrom(type))
        ) {
            // An animated background is a view added as the page's first child, which shifts the
            // children Aliucord's SettingsPage looks up by position and crashes it. Like Themer,
            // settings pages only get still backgrounds in this mode.
            if (ColorEngine.state.animatedWallpaper != null) return
            setBackground(start)
        }
    }

    private fun decorateFull(start: View, type: Class<*>, rootId: Int) {
        val name = type.name
        // These are shown as dialogs over the chat, which would make them unreadable.
        if (name == "com.discord.widgets.user.search.WidgetGlobalSearch" ||
            name == "com.discord.widgets.user.WidgetUserMentions"
        ) {
            setBackground(start)
            (start as? ViewGroup)?.let { addScrim(it, if (ColorEngine.state.animatedWallpaper != null) 1 else 0) }
        }
        var view = start
        while (view.id != rootId) view = view.parent as? View ?: return
        while (true) view = view.parent as? View ?: break
        setBackground(view)
        val darken = name == "com.discord.widgets.debugging.WidgetDebugging" ||
            name == "com.discord.widgets.search.WidgetSearch" ||
            name.contains("setting", true) ||
            SettingsPage::class.java.isAssignableFrom(type)
        if (darken) {
            val parent = start.parent as? ViewGroup ?: return
            if (parent !is FragmentContainerView &&
                parent.findViewById<View>(overlayId) == null
            ) {
                addScrim(parent, 0, overlayId)
            }
        }
    }

    private fun addScrim(parent: ViewGroup, index: Int, id: Int = View.NO_ID) {
        parent.addView(
            View(parent.context).apply {
                this.id = id
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                background = ColorDrawable(Color.argb(PAGE_SCRIM, 0, 0, 0))
            },
            index,
        )
    }

    private fun setBackground(view: View) {
        val s = ColorEngine.state
        val animated = s.animatedWallpaper
        if (animated != null) {
            val parent = view as? ViewGroup ?: return
            if (parent is FragmentContainerView || parent.findViewById<View>(backgroundId) != null) return
            SimpleDraweeView(parent.context).run {
                id = backgroundId
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                // Fresco.newDraweeControllerBuilder().setUri(uri).setAutoPlayAnimations(true).build()
                controller = b.f.g.a.a.b.a().run {
                    f(animated)
                    m = true
                    a()
                }
                parent.addView(this, 0)
            }
            if (s.overlayAlpha != 0) view.background = ColorDrawable(Color.argb(s.overlayAlpha, 0, 0, 0))
        } else {
            s.wallpaper?.let { view.background = CenterCropDrawable(it) }
        }
    }

    /** Fills its bounds with the bitmap, cropping instead of stretching. */
    private class CenterCropDrawable(private val bitmap: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val matrix = Matrix()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val scale = max(bounds.width() / bitmap.width.toFloat(), bounds.height() / bitmap.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                bounds.left + (bounds.width() - bitmap.width * scale) / 2,
                bounds.top + (bounds.height() - bitmap.height * scale) / 2,
            )
        }

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipRect(bounds)
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}
