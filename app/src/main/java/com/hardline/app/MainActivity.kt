package com.hardline.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var sip: SipClient

    private val prefs by lazy { getSharedPreferences("hardline", MODE_PRIVATE) }

    private fun getServerUrl(): String? = prefs.getString("serverUrl", null)

    private fun setServerUrl(url: String) {
        prefs.edit().putString("serverUrl", url.trim().removeSuffix("/")).apply()
    }

    private fun clearServerUrl() {
        prefs.edit().remove("serverUrl").apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        web.webViewClient = WebViewClient()

        web.addJavascriptInterface(HardlineBridge(), "HardlineNative")

        sip = SipClient(::emitToUi)

        //web.loadUrl("http://10.0.2.2:3001/")
        //clearServerUrl()
        loadUi()
    }

    private fun checkServer(
        url: String,
        onOk: () -> Unit,
        onFail: () -> Unit,
    ) {
        Thread {
            try {
                val u = java.net.URL(url)
                val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2000
                    instanceFollowRedirects = true
                }

                val code = conn.responseCode
                conn.disconnect()

                runOnUiThread {
                    if (code in 200..299) onOk() else onFail()
                }
            } catch (_: Exception) {
                runOnUiThread { onFail() }
            }
        }.start()
    }

    private fun loadUi() {
        val url = getServerUrl()
        if (url.isNullOrBlank()) {
            promptServerUrl()
            return
        }

        checkServer(
            url = url,
            onOk = {
                web.loadUrl("$url/list")
            },
            onFail = {
                promptServerUrl("Сервер недоступен")
            }
        )
    }

    private fun promptServerUrl(error: String? = null) {
        val input = android.widget.EditText(this).apply {
            hint = "https://my.phone.server.ru"
            setText(getServerUrl() ?: "")

            isSingleLine = true
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Hardline server")
            .setMessage(error)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text.toString().trim().removeSuffix("/")
                setServerUrl(url)
                loadUi()
            }
            .show()
    }

    private fun emitToUi(payloadJson: String) {
        runOnUiThread {
            val safe = JSONObject(payloadJson).toString()
            web.evaluateJavascript(
                "window.onHardlineEvent && window.onHardlineEvent($safe)",
                null
            )
        }
    }

    inner class HardlineBridge {

        @JavascriptInterface
        fun register(json: String) {
            val o = JSONObject(json)
            val bundle = SipBundle(
                username = o.getString("username"),
                password = o.getString("password"),
                host = o.getString("host"),
                realm = o.getString("realm"),
                port = o.optInt("port", 5060),
            )

            emitToUi("""{"type":"sip","state":"register_requested"}""")
            sip.register(bundle)
        }

        @JavascriptInterface
        fun call(number: String) {
            sip.call(number)
        }

        @JavascriptInterface
        fun hangup() {
            sip.hangup()
        }

        @JavascriptInterface
        fun answer() {
            sip.answer()
        }

        @JavascriptInterface
        fun setMute(mute: Boolean) {
            sip.setMute(mute)
        }

        @JavascriptInterface
        fun getServer(): String {
            return getServerUrl() ?: ""
        }

        @JavascriptInterface
        fun setServer(url: String) {
            setServerUrl(url)
            runOnUiThread { loadUi() }
        }

        @JavascriptInterface
        fun openServerDialog() {
            runOnUiThread { promptServerUrl() }
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
