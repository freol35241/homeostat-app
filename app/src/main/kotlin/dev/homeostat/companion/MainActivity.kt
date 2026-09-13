package dev.homeostat.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Status and provisioning, nothing more: the session's state, and the two
 * ways to hand the phone its config blob — scan the QR code the house repo
 * renders, or paste the same text.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 18f }
        val pasted = EditText(this).apply {
            hint = getString(R.string.paste_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(status)
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
        if (config.dashboard != null) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
