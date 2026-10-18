package com.github.yutaplug.profileidentity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.rn.user.RNUserProfile
import com.aliucord.api.rn.user.UserProfileData
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.aliucord.wrappers.users.globalName
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.view.extensions.ViewExtensions
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.widgets.settings.profile.SettingsUserProfileViewModel
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin
class ProfileIdentity : Plugin() {
    private class Field(val section: View, val input: TextInputLayout) {
        // Null until the current server value is known.
        var saved: String? = null

        val text: String get() = input.editText?.text?.toString()?.trim().orEmpty()
        val dirty: Boolean get() = saved != null && text != saved
    }

    private class Editor(val root: View, val displayName: Field, val pronouns: Field) {
        var loaded: SettingsUserProfileViewModel.ViewState.Loaded? = null
        var fetchingPronouns = false
        var saving = false

        val dirty: Boolean get() = displayName.dirty || pronouns.dirty
    }

    private val editorClass = WidgetEditUserOrGuildMemberProfile::class.java
    private val editors = WeakHashMap<WidgetEditUserOrGuildMemberProfile, Editor>()
    private val openDialogs = WeakHashMap<ConfirmDialog, Boolean>()
    private val headerStates = WeakHashMap<UserProfileHeaderView, UserProfileHeaderViewModel.ViewState.Loaded>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false

    override fun start(context: Context) {
        running = true
        val getGuildId = editorClass.getDeclaredMethod("getGuildId").apply { isAccessible = true }
        val getViewModel = editorClass.getDeclaredMethod("getViewModel").apply { isAccessible = true }
        val discardConfirmed = editorClass.getDeclaredField("discardConfirmed").apply { isAccessible = true }

        patcher.patch(
            editorClass.getDeclaredMethod("onViewBound", View::class.java),
            Hook { frame ->
                val fragment = frame.thisObject as WidgetEditUserOrGuildMemberProfile
                if (getGuildId.invoke(fragment) != null) return@Hook
                try {
                    inject(fragment, frame.args[0] as View)
                } catch (error: Throwable) {
                    logger.error("Failed to add display name and pronouns fields", error)
                }
            },
        )

        patcher.patch(
            editorClass.getDeclaredMethod("configureUI", SettingsUserProfileViewModel.ViewState::class.java),
            Hook { frame ->
                val fragment = frame.thisObject as WidgetEditUserOrGuildMemberProfile
                val editor = editors[fragment]?.takeIf { it.root === fragment.view } ?: return@Hook
                val loaded = frame.args[0] as? SettingsUserProfileViewModel.ViewState.Loaded ?: return@Hook
                editor.loaded = loaded
                loadPronouns(editor, loaded)
                refreshFab(editor)
            },
        )

        // Remember what each profile header shows, so a saved display name can reach headers on other pages.
        patcher.patch(
            UserProfileHeaderView::class.java.getDeclaredMethod(
                "updateViewState",
                UserProfileHeaderViewModel.ViewState.Loaded::class.java,
            ),
            Hook { frame ->
                headerStates[frame.thisObject as UserProfileHeaderView] =
                    frame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
            },
        )

        // The save FAB: send our fields first, then let Discord save its own changes.
        val saveClick = Class.forName(editorClass.name + "\$onViewBound\$1")
        val saveOwner = saveClick.getDeclaredField("this\$0").apply { isAccessible = true }
        patcher.patch(
            saveClick.getDeclaredMethod("onClick", View::class.java),
            PreHook { frame ->
                val fragment = saveOwner.get(frame.thisObject) as WidgetEditUserOrGuildMemberProfile
                val editor = editors[fragment]?.takeIf { it.root === fragment.view } ?: return@PreHook
                if (!editor.dirty) return@PreHook
                frame.result = null
                save(fragment, editor, null) { getViewModel.invoke(fragment) as SettingsUserProfileViewModel }
            },
        )

        // Discord's discard prompt only knows about its own fields.
        val backPress = Class.forName(editorClass.name + "\$configureUI\$3")
        val backOwner = backPress.getDeclaredField("this\$0").apply { isAccessible = true }
        val backState = backPress.getDeclaredField("\$viewState").apply { isAccessible = true }
        patcher.patch(
            backPress.declaredMethods.single { it.name == "call" && it.returnType == Boolean::class.javaObjectType },
            PreHook { frame ->
                val fragment = backOwner.get(frame.thisObject) as WidgetEditUserOrGuildMemberProfile
                val editor = editors[fragment]?.takeIf { it.root === fragment.view } ?: return@PreHook
                val loaded = backState.get(frame.thisObject) as? SettingsUserProfileViewModel.ViewState.Loaded
                    ?: return@PreHook
                val confirmed = discardConfirmed.get(fragment) as AtomicBoolean
                if (loaded.isEditingBio || loaded.isDirty || !editor.dirty || confirmed.get()) return@PreHook
                frame.result = true
                confirmDiscard(fragment, confirmed)
            },
        )
    }

    private fun inject(fragment: WidgetEditUserOrGuildMemberProfile, root: View) {
        val container = root.findViewById<LinearLayout>(Utils.getResId("profile_container", "id")) ?: return
        val bioHeader = root.findViewById<View>(Utils.getResId("bio_header", "id")) ?: return
        editors.remove(fragment)?.let { removeSections(it) }

        val me = StoreStream.getUsers().me
        val displayName = createField(root, "Display Name", DISPLAY_NAME_MAX_LENGTH) ?: return
        val pronouns = createField(root, "Pronouns", PRONOUNS_MAX_LENGTH) ?: return
        val editor = Editor(root, displayName, pronouns)

        displayName.input.placeholderText = me.username
        setSaved(editor, displayName, me.globalName.orEmpty())
        ViewExtensions.setEnabledAndAlpha(pronouns.input, false, DISABLED_ALPHA)

        val index = container.indexOfChild(bioHeader)
        container.addView(displayName.section, index)
        container.addView(pronouns.section, index + 1)
        editors[fragment] = editor
    }

    /** Takes Discord's own nickname section (header, card and text input) from a fresh copy of this screen. */
    private fun createField(root: View, title: String, maxLength: Int): Field? {
        val template = LayoutInflater.from(root.context)
            .inflate(Utils.getResId("widget_settings_user_profile", "layout"), null, false)
        val section = template.findViewById<View>(Utils.getResId("nick_container", "id")) ?: return null
        val header = section.findViewById<TextView>(Utils.getResId("nickname_header", "id")) ?: return null
        val input = section.findViewById<TextInputLayout>(Utils.getResId("set_nickname_text", "id")) ?: return null
        val editText = input.editText ?: return null
        (section.parent as ViewGroup).removeView(section)
        clearIds(section)
        section.visibility = View.VISIBLE

        header.text = title
        input.hint = title
        input.setEndIconContentDescription("Clear")
        input.setEndIconOnClickListener { editText.setText("") }
        editText.filters = editText.filters.filterNot { it is InputFilter.LengthFilter }
            .plus(InputFilter.LengthFilter(maxLength)).toTypedArray()
        editText.clearFocus()
        return Field(section, input)
    }

    private fun clearIds(view: View) {
        view.id = View.NO_ID
        if (view is ViewGroup) for (i in 0 until view.childCount) clearIds(view.getChildAt(i))
    }

    private fun setSaved(editor: Editor, field: Field, value: String) {
        val editText = field.input.editText ?: return
        val firstLoad = field.saved == null
        field.saved = value
        if (firstLoad) {
            editText.setText(value)
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    field.input.isEndIconVisible = !s.isNullOrEmpty()
                    refreshFab(editor)
                }
            })
            ViewExtensions.setEnabledAndAlpha(field.input, true, DISABLED_ALPHA)
        }
        field.input.isEndIconVisible = editText.text.isNotEmpty()
        refreshFab(editor)
    }

    private fun loadPronouns(editor: Editor, loaded: SettingsUserProfileViewModel.ViewState.Loaded) {
        if (editor.pronouns.saved != null || editor.fetchingPronouns) return
        val cached = try {
            (loaded.userProfile as? RNUserProfile)?.let { it.userProfile?.pronouns.orEmpty() }
        } catch (_: Throwable) {
            null
        }
        if (cached != null) {
            setSaved(editor, editor.pronouns, cached)
            return
        }

        editor.fetchingPronouns = true
        val userId = loaded.user.id
        Utils.threadPool.execute {
            val pronouns = try {
                Http.Request.newDiscordRNRequest("/users/$userId/profile").use { request ->
                    request.setRequestTimeout(REQUEST_TIMEOUT)
                    request.execute().use { response ->
                        check(response.ok()) { "HTTP ${response.statusCode}" }
                        val body = GsonUtils.fromJson(response.text(), Map::class.java)
                        (body?.get("user_profile") as? Map<*, *>)?.get("pronouns") as? String ?: ""
                    }
                }
            } catch (error: Throwable) {
                logger.error("Failed to load pronouns", error)
                null
            }
            editor.root.post {
                editor.fetchingPronouns = false
                if (!running) return@post
                if (pronouns != null) {
                    setSaved(editor, editor.pronouns, pronouns)
                } else {
                    Utils.showToast("Could not load your pronouns")
                }
            }
        }
    }

    private fun refreshFab(editor: Editor) {
        val fab = editor.root.findViewById<View>(Utils.getResId("save_fab", "id")) ?: return
        val loaded = editor.loaded
        val show = loaded?.showSaveFab == true || (editor.dirty && loaded?.isEditingBio != true)
        fab.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setDimmed(editor: Editor, dimmed: Boolean) {
        editor.root.findViewById<DimmerView>(Utils.getResId("dimmer_view", "id"))?.setDimmed(dimmed)
    }

    private class CaptchaRequired(val body: CaptchaErrorBody) : Exception("Captcha required")

    private fun save(
        fragment: WidgetEditUserOrGuildMemberProfile,
        editor: Editor,
        captcha: CaptchaHelper.CaptchaPayload? = null,
        viewModel: () -> SettingsUserProfileViewModel,
    ) {
        if (editor.saving) return
        editor.saving = true
        setDimmed(editor, true)
        fragment.view?.let { fragment.hideKeyboard(it) }

        val newName = editor.displayName.text.takeIf { editor.displayName.dirty }
        val newPronouns = editor.pronouns.text.takeIf { editor.pronouns.dirty }
        val nativeDirty = editor.loaded?.isDirty == true
        Utils.threadPool.execute {
            var error: String? = null
            var captchaBody: CaptchaErrorBody? = null
            // A solved captcha belongs to the first request still left to send, which is the one that asked for it.
            var pendingCaptcha = captcha
            fun attempt(label: String, block: (CaptchaHelper.CaptchaPayload?) -> Unit): Boolean = try {
                block(pendingCaptcha.also { pendingCaptcha = null })
                true
            } catch (e: CaptchaRequired) {
                if (captchaBody == null) captchaBody = e.body
                false
            } catch (e: Throwable) {
                logger.error("Failed to save $label", e)
                if (error == null) error = "$label: ${e.message ?: "could not save"}"
                false
            }

            val nameSaved = newName != null && attempt("Display name") {
                patch("/users/@me", JSONObject().put("global_name", newName.ifEmpty { JSONObject.NULL }), it)
            }
            val pronounsSaved = newPronouns != null && attempt("Pronouns") {
                patch("/users/@me/profile", JSONObject().put("pronouns", newPronouns), it)
            }

            editor.root.post {
                editor.saving = false
                if (!running) return@post
                if (nameSaved) {
                    setSaved(editor, editor.displayName, newName)
                    applyDisplayName(editor, newName)
                }
                if (pronounsSaved) {
                    setSaved(editor, editor.pronouns, newPronouns)
                    applyPronouns(editor, newPronouns)
                }
                if (nameSaved || pronounsSaved) refreshHeader(editor)
                val attached = fragment.isAdded && fragment.view === editor.root
                val pendingCaptchaBody = captchaBody
                when {
                    pendingCaptchaBody != null && attached -> {
                        setDimmed(editor, false)
                        error?.let { Utils.showToast(it) }
                        showCaptcha(fragment, editor, pendingCaptchaBody, viewModel)
                    }

                    error == null && pendingCaptchaBody == null && nativeDirty && attached -> {
                        // Discord un-dims the page once its own request finishes.
                        viewModel().saveChanges(editor.root.context)
                    }

                    else -> {
                        setDimmed(editor, false)
                        error?.let { Utils.showToast(it) }
                    }
                }
            }
        }
    }

    /**
     * Discord's user equality ignores Aliucord's globalName field, so a name-only USER_UPDATE never replaces the
     * cached users. Write the new name onto the instances the stores hand out instead.
     */
    private fun applyDisplayName(editor: Editor, name: String) {
        val value = name.ifEmpty { null }
        try {
            val users = StoreStream.getUsers()
            val me = users.me
            me.globalName = value
            (users.users[me.id] as? CoreUser)?.globalName = value
            editor.loaded?.user?.globalName = value
            updateOtherHeaders(editor, me.id, value)
        } catch (error: Throwable) {
            logger.error("Failed to update the cached display name", error)
        }
    }

    /**
     * Other pages' headers (like the main settings page) keep the user they last got, and the store never sends them
     * a new one for a name-only change. They replay that state when the page resumes, so rename it there too.
     */
    private fun updateOtherHeaders(editor: Editor, meId: Long, name: String?) {
        val ownHeader = editor.root.findViewById<View>(Utils.getResId("user_settings_profile_header_view", "id"))
        for ((header, state) in headerStates.entries.toList()) {
            val user = state.user
            if (user.id != meId) continue
            when (user) {
                is MeUser -> user.globalName = name
                is CoreUser -> user.globalName = name
                else -> continue
            }
            // refreshHeader redraws this page's own header; detached ones redraw from the renamed state on resume.
            if (header === ownHeader || !header.isAttachedToWindow) continue
            try {
                header.updateViewState(state)
            } catch (error: Throwable) {
                logger.error("Failed to refresh a profile header", error)
            }
        }
    }

    /** Aliucord's Pronouns core plugin reads pronouns from the cached profile, so update it in place. */
    private fun applyPronouns(editor: Editor, pronouns: String) {
        try {
            val field = UserProfileData::class.java.getDeclaredField("pronouns").apply { isAccessible = true }
            val meId = StoreStream.getUsers().me.id
            for (profile in listOf(StoreStream.getUserProfile().getUserProfile(meId), editor.loaded?.userProfile)) {
                (profile as? RNUserProfile)?.userProfile?.let { field.set(it, pronouns.ifEmpty { null }) }
            }
        } catch (error: Throwable) {
            logger.error("Failed to update the cached pronouns", error)
        }
    }

    /** Re-renders the profile header, which skips user objects that only differ by display name or pronouns. */
    private fun refreshHeader(editor: Editor) {
        val loaded = editor.loaded ?: return
        try {
            val header = editor.root.findViewById<UserProfileHeaderView>(
                Utils.getResId("user_settings_profile_header_view", "id"),
            ) ?: return
            val mapper = Class.forName(SettingsUserProfileViewModel::class.java.name + "\$observeHeaderViewState\$1")
            val instance = mapper.getField("INSTANCE").get(null)
            val state = mapper.getMethod("call", SettingsUserProfileViewModel.ViewState::class.java)
                .invoke(instance, loaded) as? UserProfileHeaderViewModel.ViewState.Loaded ?: return
            header.updateViewState(state)
        } catch (error: Throwable) {
            logger.error("Failed to refresh the profile header", error)
        }
    }

    /** Lets the user solve Discord's captcha in the native sheet, then saves again with the solved token. */
    private fun showCaptcha(
        fragment: WidgetEditUserOrGuildMemberProfile,
        editor: Editor,
        body: CaptchaErrorBody,
        viewModel: () -> SettingsUserProfileViewModel,
    ) {
        // Kotlin resolves WidgetCaptchaBottomSheet.Companion to the nested class, not Java's static field.
        val sheet = WidgetCaptchaBottomSheet::class.java.getField("Companion").get(null)
            as WidgetCaptchaBottomSheet.Companion
        sheet.registerForResult(fragment, CAPTCHA_REQUEST_KEY) { _, payload ->
            if (running && fragment.isAdded && fragment.view === editor.root && editor.dirty) {
                save(fragment, editor, payload, viewModel)
            }
            kotlin.Unit.a
        }
        sheet.show(fragment.parentFragmentManager, CAPTCHA_REQUEST_KEY, body) {
            if (running) Utils.showToast("Captcha not completed, changes were not saved")
            kotlin.Unit.a
        }
    }

    private fun patch(route: String, body: JSONObject, captcha: CaptchaHelper.CaptchaPayload?) {
        captcha?.let {
            body.put("captcha_key", it.captchaKey)
            it.captchaRqtoken?.takeIf { token -> token.isNotEmpty() }?.let { token -> body.put("captcha_rqtoken", token) }
        }
        Http.Request.newDiscordRNRequest(route, "PATCH").use { request ->
            request.setRequestTimeout(REQUEST_TIMEOUT).setHeader("Content-Type", "application/json")
            captcha?.let {
                request.setHeader("X-Captcha-Key", it.captchaKey)
                it.captchaRqtoken?.takeIf { token -> token.isNotEmpty() }?.let { token ->
                    request.setHeader("X-Captcha-Rqtoken", token)
                }
            }
            request.executeWithBody(body.toString()).use { response ->
                if (!response.ok()) {
                    // Response.text() refuses non-2xx responses, so read Discord's error body directly.
                    val text = try {
                        request.conn.errorStream?.bufferedReader()?.use { it.readText() }
                    } catch (_: Throwable) {
                        null
                    }
                    logger.warn("PATCH $route failed with HTTP ${response.statusCode}: $text")
                    if (text != null && text.contains("captcha_key")) {
                        val parser = CaptchaErrorBody::class.java.getField("Companion").get(null)
                            as CaptchaErrorBody.Companion
                        parser.createFromString(text)?.let { throw CaptchaRequired(it) }
                    }
                    throw IllegalStateException(errorMessage(text) ?: "HTTP ${response.statusCode}")
                }
            }
        }
    }

    /** Picks the most specific message out of Discord's error body. */
    private fun errorMessage(text: String?): String? {
        val body = try {
            GsonUtils.fromJson(text, Map::class.java)
        } catch (_: Throwable) {
            null
        } ?: return null
        return findFieldError(body["errors"]) ?: body["message"] as? String
    }

    private fun findFieldError(node: Any?): String? {
        if (node !is Map<*, *>) return null
        ((node["_errors"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("message")?.let { return it as? String }
        for (child in node.values) findFieldError(child)?.let { return it }
        return null
    }

    private fun confirmDiscard(fragment: WidgetEditUserOrGuildMemberProfile, confirmed: AtomicBoolean) {
        val dialog = ConfirmDialog()
        dialog
            .setTitle(fragment.getString(Utils.getResId("discard_changes", "string")))
            .setDescription(fragment.getString(Utils.getResId("discard_changes_description", "string")))
            .setOnOkListener {
                confirmed.set(true)
                dialog.dismiss()
                fragment.activity?.onBackPressed()
            }.show(fragment.parentFragmentManager, "ProfileIdentityDiscard")
        openDialogs[dialog] = true
    }

    private fun removeSections(editor: Editor) {
        for (field in listOf(editor.displayName, editor.pronouns)) {
            (field.section.parent as? ViewGroup)?.removeView(field.section)
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        val cleanup = Runnable {
            openDialogs.keys.toList().forEach { if (it.isAdded) it.dismissAllowingStateLoss() }
            openDialogs.clear()
            for (editor in editors.values) {
                removeSections(editor)
                editor.loaded?.let { loaded ->
                    editor.root.findViewById<View>(Utils.getResId("save_fab", "id"))?.visibility =
                        if (loaded.showSaveFab) View.VISIBLE else View.GONE
                }
            }
            editors.clear()
            headerStates.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else mainHandler.post(cleanup)
    }

    companion object {
        private const val DISPLAY_NAME_MAX_LENGTH = 32
        private const val PRONOUNS_MAX_LENGTH = 40
        private const val DISABLED_ALPHA = 0.3f
        private const val REQUEST_TIMEOUT = 15_000
        private const val CAPTCHA_REQUEST_KEY = "ProfileIdentityCaptcha"
    }
}
