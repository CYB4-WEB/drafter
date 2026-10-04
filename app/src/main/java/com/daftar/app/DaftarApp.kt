package com.daftar.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.planner.Planner
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class DaftarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Storage.init(this)
        Planner.init(this)
        PDFBoxResourceLoader.init(this)
        com.daftar.app.ink.InkRender.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_REMINDERS, getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DAILY, getString(R.string.channel_daily), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val CH_REMINDERS = "reminders"
        const val CH_DAILY = "daily"
    }
}
