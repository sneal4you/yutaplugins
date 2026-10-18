package com.github.yutaplug.quickdownload

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import java.lang.ref.WeakReference

/** Adds a download button on inline image and video attachments, like the one on file attachments. */
@AliucordPlugin
@Suppress("unused")
class QuickDownload : Plugin() {
    private val buttonId = View.generateViewId()
    private val buttons = mutableListOf<WeakReference<ImageView>>()

    override fun start(context: Context) {
        patcher.patch(
            WidgetChatListAdapterItemAttachment::class.java,
            "configureUI",
            arrayOf(WidgetChatListAdapterItemAttachment.Model::class.java),
            Hook { call ->
                val holder = call.thisObject as WidgetChatListAdapterItemAttachment
                val model = call.args[0] as WidgetChatListAdapterItemAttachment.Model
                val attachment = model.attachmentEntry.attachment
                val media = holder.itemView.findViewById<InlineMediaView>(
                    Utils.getResId("chat_list_item_attachment_inline_media", "id"),
                ) ?: return@Hook
                val type = attachment.e()
                val visible = media.visibility == View.VISIBLE &&
                    !model.isSpoilerHidden &&
                    (type == MessageAttachmentType.IMAGE || type == MessageAttachmentType.VIDEO)
                if (!visible) {
                    media.findViewById<View>(buttonId)?.visibility = View.GONE
                    return@Hook
                }

                val button = media.findViewById<ImageView>(buttonId) ?: createButton(media)
                button.visibility = View.VISIBLE
                button.isEnabled = true
                button.alpha = ENABLED_ALPHA
                button.bringToFront()
                button.setOnClickListener {
                    val started = holder.adapter.eventHandler.onQuickDownloadClicked(
                        Uri.parse(attachment.f()),
                        attachment.a(),
                    )
                    button.isEnabled = !started
                    button.alpha = DISABLED_ALPHA
                }
            },
        )
    }

    // Matches InlineMediaView's own volume toggle (inline_media_view.xml), placed in the opposite corner.
    private fun createButton(media: InlineMediaView): ImageView {
        val context = media.context
        val size = DimenUtils.dpToPx(32)
        val margin = DimenUtils.dpToPx(8)
        return ImageView(context).apply {
            id = buttonId
            setBackgroundResource(Utils.getResId("drawable_circle_primary_900", "drawable"))
            setImageResource(Utils.getResId("ic_file_download_white_24dp", "drawable"))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(margin, margin, margin, margin)
            contentDescription = context.getString(Utils.getResId("download", "string"))
            media.addView(
                this,
                FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
                    bottomMargin = margin
                    marginEnd = margin
                },
            )
            buttons.removeAll { it.get() == null }
            buttons.add(WeakReference(this))
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        buttons.forEach { reference ->
            val button = reference.get() ?: return@forEach
            (button.parent as? ViewGroup)?.removeView(button)
        }
        buttons.clear()
    }

    private companion object {
        const val ENABLED_ALPHA = 0.6f
        const val DISABLED_ALPHA = 0.3f
    }
}
