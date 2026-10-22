package com.taptranslator.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.taptranslator.app.util.AppLogger

class TapTranslatorApp : Application() {

    companion object {
        const val CHANNEL_ID = "tap_translator_service_channel"
        const val CHANNEL_NAME = "화면 번역 서비스"
        lateinit var instance: TapTranslatorApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.init(this)
        AppLogger.i("App", "Application onCreate: TapTranslatorApp initialized")
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "화면 번역 플로팅 버튼 및 캡처 서비스 알림"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
