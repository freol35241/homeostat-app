package dev.homeostat.companion

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * The scaffold's only screen: enough to install, launch and confirm the
 * toolchain end to end, and nothing more.
 *
 * The app's real surface is a foreground service holding one MQTT session
 * (see README.md); this screen becomes the status and provisioning view
 * when there is something to show.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.placeholder)
                textSize = 18f
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
