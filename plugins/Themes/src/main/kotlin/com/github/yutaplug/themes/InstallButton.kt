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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.aliucord.Constants
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.R
import java.io.File
import java.net.URLDecoder
import java.util.regex.Pattern

/** Adds "Install <theme>" rows to the message actions of theme messages in the Aliucord server. */
internal object InstallButton {
    private val tagId = View.generateViewId()
    private val jsonLink = Pattern.compile("https://\\S+\\.json")
    private val channels = setOf(Constants.THEMES_CHANNEL_ID, THEME_DEV_CHANNEL, THEME_SUPPORT_CHANNEL)

    fun install(patcher: PatcherAPI, onInstalled: (ThemeFile) -> Unit) {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { param ->
                val actions = param.thisObject as WidgetChatListActions
                val layout = (actions.view as? NestedScrollView)?.getChildAt(0) as? ViewGroup ?: return@Hook
                if (layout.findViewWithTag<View>(tagId) != null) return@Hook
                val message = (param.args[0] as WidgetChatListActions.Model).message
                if (message.channelId !in channels) return@Hook

                val links = LinkedHashMap<String, String>()
                message.attachments?.forEach { if (it.url.endsWith(".json")) links[it.filename] = it.url }
                val matcher = jsonLink.matcher(message.content.orEmpty())
                while (matcher.find()) matcher.group().let { links[it.substringAfterLast('/')] = it }
                if (links.isEmpty()) return@Hook

                val copy = layout.findViewById<TextView>(Utils.getResId("dialog_chat_actions_copy", "id"))
                val emojiTray = Utils.getResId("dialog_chat_actions_add_reaction_emojis_list", "id")
                var index = layout.findViewById<View>(emojiTray)?.let { layout.indexOfChild(it) + 1 } ?: 1
                links.forEach { (fileName, url) ->
                    val decoded = URLDecoder.decode(fileName, "UTF-8")
                    val pretty = decoded.removeSuffix(".json").replace('_', ' ').replace('-', ' ')
                    val row = TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        tag = tagId
                        text = "Install $pretty"
                        val iconId = Utils.getResId("ic_theme_24dp", "drawable")
                        val icon = ContextCompat.getDrawable(context, iconId)?.mutate()
                        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                        if (copy != null) {
                            setTextColor(copy.textColors)
                            TextViewCompat.setCompoundDrawableTintList(
                                this,
                                TextViewCompat.getCompoundDrawableTintList(copy),
                            )
                        }
                        setOnClickListener {
                            actions.dismiss()
                            download(decoded, url, pretty, onInstalled)
                        }
                    }
                    layout.addView(row, index++)
                }
            },
        )
    }

    private fun download(fileName: String, url: String, pretty: String, onInstalled: (ThemeFile) -> Unit) {
        val safeName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val file = File(THEME_DIR, if (safeName.endsWith(".json")) safeName else "$safeName.json")
        Utils.threadPool.execute {
            try {
                THEME_DIR.mkdirs()
                Http.Request(url).use { it.execute().apply { assertOk() }.saveToFile(file) }
                // Fail early on files that are not themes.
                val theme = ThemeFile(file)
                Utils.mainThread.post { onInstalled(theme) }
            } catch (error: Throwable) {
                logger.errorToast("Failed to install theme $pretty", error)
            }
        }
    }
}
