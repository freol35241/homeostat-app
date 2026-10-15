package dev.homeostat.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** A provisioned phone comes back up holding its session, without being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ConfigStore(context).load() != null) {
            Log.i("BootReceiver", "boot completed, starting the service")
            CompanionService.start(context)
        }
    }
}
