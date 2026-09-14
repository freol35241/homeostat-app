package dev.homeostat.companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * The opt-in fix (companion-protocol.md, "position"): off by default, per
 * phone, and only while away — inside the fence the router already knows,
 * and a trail of the sofa is what makes people stop running these things.
 * Balanced power, minutes apart, and only after real movement.
 */
object Positions {
    private const val TAG = "Positions"
    private const val INTERVAL_MS = 5 * 60_000L
    private const val MIN_DISTANCE_M = 200f

    /** Starts or stops updates from the toggle and the last known side of the fence. */
    @SuppressLint("MissingPermission") // Geofences.permitted() is the check; lint cannot follow it.
    fun reconcile(context: Context) {
        val store = ConfigStore(context)
        val client = LocationServices.getFusedLocationProviderClient(context)
        val wanted = store.sharePosition && store.lastPresence == false && Geofences.permitted(context)
        if (wanted) {
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                .build()
            client.requestLocationUpdates(request, pending(context))
                .addOnFailureListener { Log.w(TAG, "requesting updates failed", it) }
        } else {
            client.removeLocationUpdates(pending(context))
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PositionReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class PositionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fix = LocationResult.extractResult(intent)?.lastLocation ?: return
        val battery = context.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val position = Position(
            lat = fix.latitude,
            lon = fix.longitude,
            accuracyM = fix.accuracy.takeIf { fix.hasAccuracy() },
            batteryPercent = battery,
            fixedAtEpochS = fix.time / 1000,
        )
        CompanionService.publish(context, "person/position", position.toJson())
    }
}
