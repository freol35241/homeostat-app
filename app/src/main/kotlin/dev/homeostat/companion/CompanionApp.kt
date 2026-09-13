package dev.homeostat.companion

import android.app.Application

class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // A provisioned phone holds its session whenever the app is alive,
        // whichever screen brought it up.
        if (ConfigStore(this).load() != null) CompanionService.start(this)
        Geofences.register(this)
    }
}
