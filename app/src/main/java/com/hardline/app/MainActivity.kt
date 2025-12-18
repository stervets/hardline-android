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

        web.loadUrl("http://10.0.2.2:3001/")
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
            emitToUi("""{"type":"call_state","state":"calling","to":${jsonStr(number)}}""")
        }

        @JavascriptInterface
        fun hangup() {
            emitToUi("""{"type":"call_state","state":"ended"}""")
        }

        @JavascriptInterface
        fun answer() {
            emitToUi("""{"type":"call_state","state":"active"}""")
        }

        @JavascriptInterface
        fun setMute(mute: Boolean) {}
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
