package com.daftar.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.Storage

/**
 * AI settings. The Gemini API key is typed by the user in Settings → AI and kept only in this app's private
 * preferences on the device — it is never part of the source code, the APK or any export/backup.
 */
object AiPrefs {
    private const val FILE = "daftar_ai"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    private val sp: SharedPreferences by lazy { Storage.appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /** Observable so the UI reacts when the key is added / removed. */
    var apiKey by mutableStateOf("")
        private set
    var model by mutableStateOf(DEFAULT_MODEL)
        private set
    /** The one-time privacy notice (free tier: Google may use prompts to improve its products) was accepted. */
    var privacyAccepted by mutableStateOf(false)
        private set

    private var loaded = false
    fun init() {
        if (loaded) return
        loaded = true
        apiKey = sp.getString("key", "") ?: ""
        model = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        privacyAccepted = sp.getBoolean("privacy", false)
    }

    val hasKey: Boolean get() { init(); return apiKey.isNotBlank() }

    fun saveKey(k: String) { init(); apiKey = k.trim(); sp.edit().putString("key", apiKey).apply() }
    fun saveModel(m: String) { init(); model = m.trim().ifBlank { DEFAULT_MODEL }; sp.edit().putString("model", model).apply() }
    fun acceptPrivacy() { init(); privacyAccepted = true; sp.edit().putBoolean("privacy", true).apply() }
    /** Shows the one-time privacy notice again before the next request. */
    fun resetPrivacy() { init(); privacyAccepted = false; sp.edit().putBoolean("privacy", false).apply() }
}
