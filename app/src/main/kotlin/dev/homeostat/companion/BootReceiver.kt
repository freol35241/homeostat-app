package dev.homeostat.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A provisioned phone comes back up holding its session, without being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ConfigStore(context).load() != null) {
            CompanionService.start(context)
        }
    }
}
