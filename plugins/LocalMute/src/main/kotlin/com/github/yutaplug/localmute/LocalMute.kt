package com.github.yutaplug.localmute

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.channel.Channel
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInput
import java.lang.ref.WeakReference

/** Locally blocks the chat input in chosen channels (e.g. scam honeypots) while keeping reactions usable. */
@AliucordPlugin
class LocalMute : Plugin() {
    private val muted = HashSet<Long>()

    // Last store state each chat input view model saw, replayed so a toggle applies without reopening the channel.
    private var chatInput: Pair<WeakReference<Any>, Any>? = null
    private var flexInput: Pair<WeakReference<Any>, Any>? = null

    private val chatInputHandler = ChatInputViewModel::class.java
        .getDeclaredMethod("handleStoreState", ChatInputViewModel.StoreState::class.java)
        .apply { isAccessible = true }
    private val flexInputHandler = AppFlexInputViewModel::class.java
        .getDeclaredMethod("handleStoreState", AppFlexInputViewModel.StoreState::class.java)
        .apply { isAccessible = true }

    override fun start(context: Context) {
        settings.getString(KEY_MUTED, "").split(',').mapNotNullTo(muted) { it.toLongOrNull() }

        // Drives the chat input, attachments/send button and sticker sending. Reactions use a separate permission.
        patcher.patch(
            PermissionUtils::class.java,
            "hasAccessWrite",
            arrayOf(Channel::class.java, Long::class.javaObjectType),
            Hook { frame ->
                val channel = frame.args[0] as? Channel ?: return@Hook
                if (channel.k() in muted) frame.result = false
            },
        )
        patcher.patch(
            WidgetChatInput::class.java,
            "getHint",
            arrayOf(Context::class.java, Channel::class.java, Boolean::class.java, Boolean::class.java),
            Hook { frame ->
                val channel = frame.args[1] as? Channel ?: return@Hook
                if (frame.args[2] == false && channel.k() in muted) frame.result = HINT
            },
        )
        patcher.patch(
            chatInputHandler,
            PreHook { frame -> chatInput = WeakReference(frame.thisObject) to frame.args[0] },
        )
        patcher.patch(
            flexInputHandler,
            PreHook { frame -> flexInput = WeakReference(frame.thisObject) to frame.args[0] },
        )
        patcher.patch(
            WidgetChannelsListItemChannelActions::class.java,
            "configureUI",
            arrayOf(WidgetChannelsListItemChannelActions.Model::class.java),
            Hook { frame ->
                val model = frame.args[0] as? WidgetChannelsListItemChannelActions.Model ?: return@Hook
                try {
                    configureSheet(frame.thisObject as WidgetChannelsListItemChannelActions, model.channel)
                } catch (error: Exception) {
                    logger.error("Failed to add mute entry", error)
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        refreshInput()
        chatInput = null
        flexInput = null
    }

    private fun configureSheet(sheet: WidgetChannelsListItemChannelActions, channel: Channel) {
        val root = sheet.view ?: return
        val anchor = root.findViewById<View>(Utils.getResId("text_action_mark_as_read", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        // Discord reconfigures the same sheet when the channel model changes.
        val existing = parent.findViewWithTag<TextView>(TAG)
        if (channel.D() !in SUPPORTED_CHANNEL_TYPES) {
            existing?.let(parent::removeView)
            return
        }
        val row = existing ?: cloneRow(parent.context)?.also {
            val mute = parent.findViewById<View>(Utils.getResId("text_action_mute", "id"))
            val index = mute?.let(parent::indexOfChild) ?: -1
            parent.addView(it, if (index >= 0) index + 1 else parent.childCount)
        } ?: return
        val id = channel.k()
        row.text = if (id in muted) "Unmute Yourself" else "Mute Yourself"
        row.setOnClickListener {
            sheet.dismiss()
            val nowMuted = id !in muted
            if (nowMuted) muted.add(id) else muted.remove(id)
            settings.setString(KEY_MUTED, muted.joinToString(","))
            Utils.showToast(if (nowMuted) "You can no longer type in this channel" else "You can type in this channel again")
            refreshInput()
        }
    }

    /** Inflates a real row from Discord's sheet layout so text appearance, padding and touch feedback match. */
    private fun cloneRow(context: Context): TextView? {
        val layoutId = Utils.getResId("widget_channels_list_item_actions", "layout")
        val rowId = Utils.getResId("text_action_mark_as_read", "id")
        if (layoutId == 0 || rowId == 0) return null
        val template = LayoutInflater.from(context).inflate(layoutId, null)
        val row = template.findViewById<TextView>(rowId) ?: return null
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.generateViewId()
        row.tag = TAG
        row.visibility = View.VISIBLE
        val attr = Utils.getResId("ic_lock", "attr")
        val icon = if (attr != 0) DrawableCompat.getThemedDrawableRes(context, attr) else 0
        if (icon != 0) row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        return row
    }

    /** Replays the last store state through both input view models so the change shows immediately. */
    private fun refreshInput() {
        Utils.mainThread.post {
            try {
                chatInput?.let { (vm, state) -> vm.get()?.let { chatInputHandler.invoke(it, state) } }
                flexInput?.let { (vm, state) -> vm.get()?.let { flexInputHandler.invoke(it, state) } }
            } catch (error: Exception) {
                logger.error("Failed to refresh chat input", error)
            }
        }
    }

    companion object {
        private const val TAG = "LocalMute.Toggle"
        private const val KEY_MUTED = "mutedChannels"
        private const val HINT = "You muted yourself in this channel"

        // Text, DM, group DM, announcement and thread channels.
        private val SUPPORTED_CHANNEL_TYPES = setOf(0, 1, 3, 5, 10, 11, 12)
    }
}
