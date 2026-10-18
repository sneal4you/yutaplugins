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

package com.github.yutaplug.themes.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.github.yutaplug.themes.ALLOWED_RESOURCE_DOMAINS_PATTERN
import com.lytefast.flexinput.R
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * A page of text fields bound to the keys of one theme section. Invalid values are not stored.
 * Fields for which [fileKind] returns a kind get a button that fills them with a file from the device.
 */
internal class FormPage(
    private val title: String,
    private val keys: Array<String>,
    private val data: JSONObject,
    private val validator: (key: String, value: String) -> Boolean,
    private val fileKind: (key: String) -> ThemeFileKind? = { null },
) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(title)
        setActionBarSubtitle("Theme Editor")
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))
        val p = DimenUtils.dpToPx(16)
        keys.forEach { key ->
            val input = TextInput(requireContext(), key, data.opt(key)?.toString().orEmpty())
            val edit = input.editText
            val kind = fileKind(key)
            if (kind != null) {
                ContextCompat.getDrawable(requireContext(), Utils.getResId("ic_guild_folder", "drawable"))?.let {
                    input.setThemedEndIcon(it)
                }
                input.root.endIconContentDescription = "Pick ${kind.what} from your device"
                input.root.setEndIconOnClickListener {
                    ThemeFilePicker.open(requireActivity(), kind) { url -> edit.setText(url) }
                }
                input.root.isEndIconVisible = true
            }
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(editable: Editable?) {
                    // TextInput hides its end icon while the field is empty; the file button must stay.
                    if (kind != null) input.root.isEndIconVisible = true
                    val value = edit.text.toString().trim()
                    input.root.hint = key
                    when {
                        value.isEmpty() -> data.remove(key)
                        !validator(key, value) -> input.root.hint = "$key (invalid)"
                        else -> data.put(key, Validators.convert(key, value))
                    }
                }
            })
            linearLayout.addView(
                input,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(p, p / 2, p, p / 2) },
            )
        }
    }
}

internal object Validators {
    private val versionPattern: Pattern by lazy { Pattern.compile("^(\\d{1,3}\\.)*\\d{1,3}$") }

    fun url(
        @Suppress("UNUSED_PARAMETER") key: String,
        s: String,
    ) = ALLOWED_RESOURCE_DOMAINS_PATTERN.matcher(s).find() ||
        (s.startsWith("file://") && File(s.removePrefix("file://")).exists())

    fun manifest(key: String, s: String) = when (key) {
        "version" -> versionPattern.matcher(s).matches()
        "updater" -> url(key, s)
        else -> true
    }

    fun background(key: String, s: String) = when (key) {
        "url" -> url(key, s)
        "overlay_alpha" -> s.toIntOrNull()?.let { it in 0..0xff } == true
        "blur_radius" -> s.toDoubleOrNull()?.let { it in 0.0..25.0 } == true
        else -> true
    }

    fun convert(key: String, s: String): Any = when (key) {
        "overlay_alpha" -> s.toInt()
        "blur_radius" -> s.toDouble()
        else -> s
    }
}
