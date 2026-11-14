package dev.homeostat.companion

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Timers that fire with the screen off. A Handler's clock stops while the
 * CPU sleeps, so a keepalive ping or a backoff due in five minutes could
 * wait for hours, and the broker fires the will in the meantime. An
 * allow-while-idle alarm wakes the phone, and the battery exemption lets
 * it through Doze.
 *
 * Tasks run on the main thread inside the alarm's broadcast, which keeps
 * the CPU up only until they return: a task that waits on the network
 * holds its own wakelock.
 */
class AlarmScheduler(private val context: Context, name: String) : Scheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val action = "${context.packageName}.alarm.$name"
    private val tasks = ConcurrentHashMap<Int, Runnable>()
    private val nextId = AtomicInteger()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            tasks.remove(intent.getIntExtra(EXTRA_ID, -1))?.run()
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission") // canScheduleExactAlarms() is the check; lint cannot follow it.
    override fun schedule(delayMs: Long, task: Runnable): Cancellable {
        val id = nextId.getAndIncrement()
        tasks[id] = task
        val pending = PendingIntent.getBroadcast(
            context, id, Intent(action).setPackage(context.packageName).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        // Exact needs the alarms permission or the battery exemption; an
        // inexact alarm may come up to three quarters of its delay late.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        } else {
            Log.w(TAG, "exact alarms not permitted, $action is inexact")
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
        return Cancellable {
            tasks.remove(id)
            alarms.cancel(pending)
        }
    }

    /** Stops listening; an alarm still pending fires into nothing. */
    fun close() {
        context.unregisterReceiver(receiver)
        tasks.clear()
    }

    private companion object {
        const val TAG = "AlarmScheduler"
        const val EXTRA_ID = "id"
    }
}
