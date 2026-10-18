package dev.homeostat.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Status and provisioning, nothing more: the session's state, the setup
 * checklist, and the two ways to hand the phone its config blob — scan the
 * QR code the house repo renders, or paste the same text.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var sharePosition: MaterialSwitch
    private lateinit var pasted: EditText
    private lateinit var notificationsRow: View
    private lateinit var dndRow: View
    private lateinit var batteryRow: View
    private lateinit var locationRow: View

    // Provisioned, the scan and paste stay folded away until asked for.
    private var reprovisioning = false

    // The exemption is what keeps the session's reconnect timers honest
    // under Doze; this app is exactly the case the permission exists for.
    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // From target 35 the window is edge to edge: keep clear of the bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        statusDot = findViewById(R.id.statusDot)
        pasted = findViewById(R.id.pasted)
        notificationsRow = row(R.id.rowNotifications, R.string.setup_notifications, R.string.setup_notifications_summary) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        dndRow = row(R.id.rowDnd, R.string.setup_dnd, R.string.setup_dnd_summary) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        batteryRow = row(R.id.rowBattery, R.string.setup_battery, R.string.setup_battery_summary) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        locationRow = row(R.id.rowLocation, R.string.setup_location, R.string.setup_location_summary) { discloseLocation() }
        sharePosition = findViewById<MaterialSwitch>(R.id.sharePosition).apply {
            setOnCheckedChangeListener { _, on ->
                ConfigStore(this@MainActivity).sharePosition = on
                Positions.reconcile(this@MainActivity)
            }
        }
        findViewById<View>(R.id.openDashboard).setOnClickListener {
            startActivity(
                Intent(this, DashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
        findViewById<View>(R.id.reprovision).setOnClickListener {
            reprovisioning = true
            refresh()
        }
        findViewById<View>(R.id.scan).setOnClickListener { scan() }
        findViewById<View>(R.id.manual).setOnClickListener {
            it.visibility = View.GONE
            findViewById<View>(R.id.pastedLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.usePasted).visibility = View.VISIBLE
            pasted.requestFocus()
        }
        findViewById<View>(R.id.usePasted).setOnClickListener { provision(pasted.text.toString()) }
        findViewById<View>(R.id.pastedLayout).visibility = View.GONE
        findViewById<View>(R.id.usePasted).visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        Status.observer = { showStatus() }
        // Bypass-DND on the alert channel only takes once policy access is
        // granted, so re-create the channels on the way back from settings.
        Channels.ensure(this)
        refresh()
    }

    /** The whole screen from the config and the permissions as they stand now. */
    private fun refresh() {
        val store = ConfigStore(this)
        val config = store.load()
        val provisioned = config != null
        val hasHome = config?.home != null
        findViewById<View>(R.id.welcome).shown(!provisioned)
        findViewById<View>(R.id.statusCard).shown(provisioned)
        findViewById<View>(R.id.openDashboard).shown(config?.dashboard != null)
        findViewById<View>(R.id.setupHeader).shown(provisioned)
        findViewById<View>(R.id.setupCard).shown(provisioned)
        findViewById<View>(R.id.reprovision).shown(provisioned && !reprovisioning)
        findViewById<View>(R.id.provision).shown(!provisioned || reprovisioning)
        if (config != null) statusDetail.text = getString(R.string.status_detail, config.phone, config.host)
        showStatus()

        notificationsRow.done(getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        dndRow.done(getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
        batteryRow.done(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        locationRow.shown(hasHome)
        locationRow.done(Geofences.permitted(this))
        // Position needs the fence: it only runs while away.
        findViewById<View>(R.id.positionCard).shown(hasHome && Geofences.permitted(this))
        sharePosition.isChecked = store.sharePosition
    }

    private fun showStatus() {
        status.text = Status.text.ifEmpty { getString(R.string.status_starting) }
        val color = when (Status.tone) {
            Status.Tone.Ok -> MaterialColors.getColor(statusDot, androidx.appcompat.R.attr.colorPrimary)
            Status.Tone.Trying -> getColor(R.color.brand_set_point)
            Status.Tone.Off -> MaterialColors.getColor(statusDot, com.google.android.material.R.attr.colorOutline)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun row(id: Int, title: Int, summary: Int, fix: () -> Unit): View =
        findViewById<View>(id).apply {
            findViewById<TextView>(R.id.title).setText(title)
            findViewById<TextView>(R.id.summary).setText(summary)
            setOnClickListener { fix() }
        }

    /** Ticked and inert when done; an open circle and a way there when not. */
    private fun View.done(done: Boolean) {
        val icon = findViewById<ImageView>(R.id.icon)
        icon.setImageResource(if (done) R.drawable.ic_done else R.drawable.ic_todo)
        icon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                icon,
                if (done) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutline,
            ),
        )
        findViewById<View>(R.id.chevron).shown(!done)
        isClickable = !done
    }

    private fun View.shown(shown: Boolean) {
        visibility = if (shown) View.VISIBLE else View.GONE
    }

    // Play's prominent disclosure: what is collected, why, and that it runs
    // in the background, said in the app before the system prompt.
    private fun discloseLocation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_disclosure_title)
            .setMessage(R.string.location_disclosure)
            .setPositiveButton(R.string.location_disclosure_continue) { _, _ -> requestLocation() }
            .setNegativeButton(R.string.location_disclosure_not_now, null)
            .show()
    }

    // Android grants background location only as a second step after
    // foreground, and on 30+ that step is a settings page.
    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_FINE,
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) return
        when (requestCode) {
            REQUEST_FINE -> requestLocation()
            REQUEST_BACKGROUND -> Geofences.register(this)
        }
        refresh()
    }

    override fun onPause() {
        Status.observer = null
        super.onPause()
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { provision(it) } }
            .addOnFailureListener { toast(getString(R.string.scan_failed, it.message ?: "")) }
    }

    // Provisioning lands on the checklist, not the dashboard: the grants
    // are asked for here, and the dashboard is one tap on.
    private fun provision(toml: String) {
        val config = try {
            CompanionConfig.parse(toml)
        } catch (e: ConfigException) {
            toast(e.message ?: "")
            return
        }
        ConfigStore(this).save(toml)
        toast(getString(R.string.provisioned, config.phone, config.host))
        CompanionService.restart(this)
        Geofences.register(this)
        reprovisioning = false
        pasted.text = null
        refresh()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_FINE = 1
        const val REQUEST_BACKGROUND = 2
        const val REQUEST_NOTIFICATIONS = 3
    }
}
