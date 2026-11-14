package dev.homeostat.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A provisioned phone comes back up holding its session, without being
 * opened: after a reboot, and after an update, which kills the process.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            ConfigStore(context).load() != null
        ) {
            Log.i("BootReceiver", "$action, starting the service")
            CompanionService.start(context)
        }
    }
}
