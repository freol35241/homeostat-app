package dev.homeostat.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The launcher: the dashboard unit's page in a standalone window, which is
 * what the family bookmark lacks (design.md, "The companion app"). Same
 * page, same unit, no new bus surface — the app renders nothing itself.
 *
 * Without a dashboard URL there is nothing to show, and the status screen
 * takes over.
 */
class DashboardActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled") // The page is the house's own dashboard unit.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = ConfigStore(this).load()?.dashboard
        if (url == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
