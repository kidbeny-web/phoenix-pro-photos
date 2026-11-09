package fr.phoenixpro.agenda

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AgendaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val ch = NotificationChannel(Reminders.CHANNEL, "Rappels de rendez-vous", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Alerte avant chaque rendez-vous"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
