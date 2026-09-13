package dev.homeostat.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * The three channels. `message` and `alert` are the protocol's two
 * downward topics, kept structurally apart on the phone as they are on
 * the wire: `alert` overrides Do Not Disturb, which Android honours only
 * once the app has been granted notification-policy access.
 */
object Channels {
    const val SERVICE = "service"
    const val MESSAGE = "message"
    const val ALERT = "alert"

    /** Idempotent; called again after policy access is granted so bypass-DND takes. */
    fun ensure(context: Context) {
        val alert = NotificationChannel(ALERT, context.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(MESSAGE, context.getString(R.string.channel_message), NotificationManager.IMPORTANCE_DEFAULT),
                alert,
            ),
        )
    }
}
