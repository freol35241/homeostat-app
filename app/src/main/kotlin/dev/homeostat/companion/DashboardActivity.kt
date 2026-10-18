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
        // Back walks the page's own history first. From target 36 the system
        // no longer delivers KEYCODE_BACK; the dispatcher is the only hook,
        // and the callback is enabled only while there is somewhere to go.
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = web.goBack()
        }
        onBackPressedDispatcher.addCallback(this, back)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    back.isEnabled = view.canGoBack()
                }
            }
        }
        // Edge to edge from target 35: the page sits between the bars, on the
        // theme's background, rather than under the clock.
        val frame = FrameLayout(this).apply { addView(web) }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(frame)
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }
}
