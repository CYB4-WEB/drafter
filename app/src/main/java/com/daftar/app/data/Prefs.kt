package com.daftar.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App settings, backed by SharedPreferences and observable from Compose. */
object Prefs {
    private lateinit var sp: SharedPreferences

    var themeMode by mutableIntStateOf(0)          // 0 system, 1 light, 2 dark
        private set
    var penOnly by mutableStateOf(true)            // finger scrolls, pen draws
        private set
    var stylusButton by mutableIntStateOf(0)       // 0 eraser, 1 lasso
        private set
    var defaultPaper by mutableStateOf("lined")
        private set
    var inkLang by mutableStateOf("en-US")         // handwriting recognition
        private set
    var speechLang by mutableStateOf("ar-SA")      // dictation
        private set
    var dailySummary by mutableStateOf(true)
        private set
    var gridView by mutableStateOf(true)
        private set
    var sortMode by mutableIntStateOf(0)           // 0 name, 1 date, 2 type
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        themeMode = sp.getInt("theme", 0)
        penOnly = sp.getBoolean("penOnly", true)
        stylusButton = sp.getInt("stylusButton", 0)
        defaultPaper = sp.getString("paper", "lined")!!
        inkLang = sp.getString("inkLang", "en-US")!!
        speechLang = sp.getString("speechLang", "ar-SA")!!
        dailySummary = sp.getBoolean("daily", true)
        gridView = sp.getBoolean("grid", true)
        sortMode = sp.getInt("sort", 0)
    }

    fun putTheme(v: Int) { themeMode = v; sp.edit().putInt("theme", v).apply() }
    fun putPenOnly(v: Boolean) { penOnly = v; sp.edit().putBoolean("penOnly", v).apply() }
    fun putStylusButton(v: Int) { stylusButton = v; sp.edit().putInt("stylusButton", v).apply() }
    fun putPaper(v: String) { defaultPaper = v; sp.edit().putString("paper", v).apply() }
    fun putInkLang(v: String) { inkLang = v; sp.edit().putString("inkLang", v).apply() }
    fun putSpeechLang(v: String) { speechLang = v; sp.edit().putString("speechLang", v).apply() }
    fun putDaily(v: Boolean) { dailySummary = v; sp.edit().putBoolean("daily", v).apply() }
    fun putGrid(v: Boolean) { gridView = v; sp.edit().putBoolean("grid", v).apply() }
    fun putSort(v: Int) { sortMode = v; sp.edit().putInt("sort", v).apply() }
}
