package dev.homeostat.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** A notification was dismissed: that is the far-end receipt. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AckReceiver", "dismissed")
        CompanionService.ack(context)
    }
}
