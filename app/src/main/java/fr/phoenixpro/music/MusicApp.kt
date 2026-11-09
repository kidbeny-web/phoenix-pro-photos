package fr.phoenixpro.music

import android.app.Application

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.init(this)
    }
}
