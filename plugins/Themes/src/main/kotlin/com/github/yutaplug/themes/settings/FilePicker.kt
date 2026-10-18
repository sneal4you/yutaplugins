package com.github.yutaplug.themes.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.github.yutaplug.themes.THEME_DIR
import com.github.yutaplug.themes.ThemeIcon
import com.github.yutaplug.themes.logger
import java.io.File
import java.security.MessageDigest

/** What a theme field holds, which decides the picker's file filter and how the pick is checked. */
internal enum class ThemeFileKind(val mimeType: String, val what: String) {
    IMAGE("image/*", "an image"),

    // File managers report font MIME types inconsistently, so every file is offered and checked after.
    FONT("*/*", "a font"),
    SOUND("audio/*", "a sound"),
}

/**
 * Lets the user pick a file from the gallery or a files app, copies it into [FILES_DIR] and hands
 * its `file://` link to the callback, which is what theme fields store.
 *
 * Headless fragment attached to the activity: settings pages live inside Aliucord's fragment proxy,
 * where activity results never arrive.
 */
class ThemeFilePicker : Fragment() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        val kind = pending?.kind ?: return removeSelf()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = kind.mimeType
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Pick ${kind.what}"), REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            Utils.showToast("No gallery or files app available")
            pending = null
            removeSelf()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        val request = pending
        pending = null
        val uri = data?.data
        if (request != null && resultCode == Activity.RESULT_OK && uri != null) {
            Utils.threadPool.execute { import(uri, request) }
        }
        removeSelf()
    }

    private fun removeSelf() {
        if (!isAdded) return
        val manager = parentFragmentManager
        if (!manager.isDestroyed) manager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    private class Request(val kind: ThemeFileKind, val onPicked: (String) -> Unit)

    internal companion object {
        private const val REQUEST_CODE = 5307
        private const val TAG = "Themes.FilePicker"

        /** Where picked files are kept, next to the theme files that point at them. */
        val FILES_DIR = File(THEME_DIR, "files")

        private var pending: Request? = null

        /** Opens the picker; [onPicked] gets the `file://` link on the main thread. */
        fun open(activity: FragmentActivity, kind: ThemeFileKind, onPicked: (String) -> Unit) {
            val manager = activity.supportFragmentManager
            if (manager.findFragmentByTag(TAG) != null) return
            pending = Request(kind, onPicked)
            manager.beginTransaction().add(ThemeFilePicker(), TAG).commitAllowingStateLoss()
        }

        private fun import(uri: Uri, request: Request) {
            val result = runCatching { copy(uri, request.kind) }
            Utils.mainThread.post {
                result
                    .onSuccess { request.onPicked("file://${it.absolutePath}") }
                    .onFailure {
                        logger.error("Themes: failed to import $uri", it)
                        Utils.showToast(it.message ?: "Couldn't use that file")
                    }
            }
        }

        /** Copies [uri] into [FILES_DIR] as `name-hash.ext`, so picking the same file twice reuses it. */
        private fun copy(uri: Uri, kind: ThemeFileKind): File {
            val resolver = Utils.appContext.contentResolver
            val displayName = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty() || extension.length > 5) {
                extension = resolver.getType(uri)
                    ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    .orEmpty()
            }
            val base = displayName
                .substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .take(40)
                .ifEmpty { "file" }

            if (!FILES_DIR.exists() && !FILES_DIR.mkdirs()) throw IllegalStateException("Couldn't create $FILES_DIR")
            val temp = File(FILES_DIR, ".import-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-1")
            try {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Couldn't open that file")
                input.use { source ->
                    temp.outputStream().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            target.write(buffer, 0, read)
                        }
                    }
                }
                check(temp.length() > 0) { "That file is empty" }
                verify(kind, temp)
                val hash = digest.digest().take(4).joinToString("") { String.format("%02x", it) }
                val name = if (extension.isEmpty()) "$base-$hash" else "$base-$hash.$extension"
                val file = File(FILES_DIR, name)
                if (file.isFile && file.length() == temp.length()) return file
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                }
                return file
            } finally {
                temp.delete()
            }
        }

        private fun verify(kind: ThemeFileKind, file: File) {
            when (kind) {
                ThemeFileKind.IMAGE -> {
                    check(ThemeIcon.isImage(file)) { "That file is not a PNG, JPEG, WebP or GIF image" }
                }

                ThemeFileKind.FONT -> {
                    check(runCatching { Typeface.createFromFile(file) }.isSuccess) {
                        "That file is not a TTF or OTF font"
                    }
                }

                ThemeFileKind.SOUND -> {}
            }
        }
    }
}
