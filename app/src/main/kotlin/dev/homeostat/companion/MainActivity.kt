package dev.homeostat.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Status and provisioning, nothing more: the session's state, and the two
 * ways to hand the phone its config blob — scan the QR code the house repo
 * renders, or paste the same text.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dndAccess: Button
    private lateinit var batteryExemption: Button
    private lateinit var location: Button
    private lateinit var sharePosition: MaterialSwitch

    // The exemption is what keeps the session's reconnect timers honest
    // under Doze; this app is exactly the case the permission exists for.
    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 18f }
        dndAccess = Button(this).apply {
            text = getString(R.string.allow_dnd_bypass)
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        val pasted = EditText(this).apply {
            hint = getString(R.string.paste_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        batteryExemption = Button(this).apply {
            text = getString(R.string.allow_battery_exemption)
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
        }
        location = Button(this).apply {
            text = getString(R.string.allow_location)
            setOnClickListener { requestLocation() }
        }
        sharePosition = MaterialSwitch(this).apply {
            text = getString(R.string.share_position)
            setOnCheckedChangeListener { _, on ->
                ConfigStore(this@MainActivity).sharePosition = on
                Positions.reconcile(this@MainActivity)
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(status)
                addView(dndAccess)
                addView(batteryExemption)
                addView(location)
                addView(sharePosition)
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.scan)
                    setOnClickListener { scan() }
                })
                addView(pasted)
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.use_pasted)
                    setOnClickListener { provision(pasted.text.toString()) }
                })
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        Status.observer = { status.text = it }
        status.text = Status.text.ifEmpty { getString(R.string.status_unprovisioned) }
        // Bypass-DND on the alert channel only takes once policy access is
        // granted, so re-create the channels on the way back from settings.
        Channels.ensure(this)
        dndAccess.visibility =
            if (getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) View.GONE else View.VISIBLE
        batteryExemption.visibility =
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        val store = ConfigStore(this)
        val hasHome = store.load()?.home != null
        location.visibility = if (!hasHome || Geofences.permitted(this)) View.GONE else View.VISIBLE
        // Position needs the fence: it only runs while away.
        sharePosition.visibility = if (hasHome && Geofences.permitted(this)) View.VISIBLE else View.GONE
        sharePosition.isChecked = store.sharePosition
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
        if (config.dashboard != null) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_FINE = 1
        const val REQUEST_BACKGROUND = 2
    }
}
