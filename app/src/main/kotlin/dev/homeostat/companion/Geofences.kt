package dev.homeostat.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * The one geofence, `home`, on the platform's Geofencing API
 * (companion-protocol.md, "presence"). No location loop: the OS wakes
 * [GeofenceReceiver] on a transition and the service publishes it.
 *
 * Registration does not survive a reboot, so it is redone on every
 * process start; re-adding the same id replaces it.
 */
object Geofences {
    private const val TAG = "Geofences"
    private const val ID = "home"

    fun permitted(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Registers when there is a home and the permissions to watch it; a no-op otherwise. */
    @SuppressLint("MissingPermission") // permitted() is the check; lint cannot follow it.
    fun register(context: Context) {
        val home = ConfigStore(context).load()?.home ?: return
        if (!permitted(context)) return
        val fence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(home.lat, home.lon, home.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // The initial trigger publishes the current side of the fence on
        // registration, which is the presence after a reboot or a re-provision.
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(fence)
            .build()
        // Mutable: Play services fills the transition into the intent.
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, GeofenceReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        LocationServices.getGeofencingClient(context).addGeofences(request, pending)
            .addOnFailureListener { Log.w(TAG, "registering home failed", it) }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("Geofences", "transition error ${event.errorCode}")
            return
        }
        val presence = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        ConfigStore(context).lastPresence = presence
        CompanionService.publish(context, "person/presence", presence.toString())
        // Away starts the opt-in position updates; home stops them.
        Positions.reconcile(context)
    }
}
