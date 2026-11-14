package dev.homeostat.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The launcher: the dashboard unit's page in a standalone window, which is
 * what the family bookmark lacks (design.md, "The companion app"). Same
 * page, same unit, no new bus surface — the app renders nothing itself.
 *
 * Without a dashboard URL there is nothing to show, and the status screen
 * takes over.
 */
class DashboardActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled") // The page is the house's own dashboard unit.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(CompanionService.EXTRA_ACK)?.let { CompanionService.ack(this, it) }
        val url = ConfigStore(this).load()?.dashboard
        if (url == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // Back walks the page's own history first, and past its start goes
        // to the status screen, the way there from inside the dashboard.
        // This window finishes, as the status screen's own way back here
        // does, so the two never stack and a back from there leaves. From
        // target 36 the system no longer delivers KEYCODE_BACK; the
        // dispatcher is the only hook.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    startActivity(Intent(this@DashboardActivity, MainActivity::class.java))
                    finish()
                }
            }
        })
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        // Edge to edge from target 35: the page sits between the bars, on the
        // theme's background, rather than under the clock.
        val frame = FrameLayout(this).apply { addView(web) }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // Consumed, or the WebView hands them on to the page as
            // env(safe-area-inset-*) and its bottom tabs pad for the bar twice.
            WindowInsetsCompat.CONSUMED
        }
        setContentView(frame)
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }
}
