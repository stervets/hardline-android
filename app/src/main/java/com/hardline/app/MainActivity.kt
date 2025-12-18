package com.hardline.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()

        // JS -> Native
        web.addJavascriptInterface(HardlineBridge(), "HardlineNative")

        // Native -> JS (через callback)
        sip = SipClient { payloadJson ->
            runOnUiThread {
                // payloadJson должен быть валидным JSON-объектом
                val safe = JSONObject(payloadJson).toString()
                web.evaluateJavascript(
                    "window.onHardlineEvent && window.onHardlineEvent($safe)",
                    null
                )
            }
        }

        web.loadUrl("http://10.0.2.2:3001/")
    }

    inner class HardlineBridge {

        @JavascriptInterface
        fun register(json: String) {
            try {
                val o = JSONObject(json)

                val bundle = SipBundle(
                    username = o.getString("username"),
                    password = o.getString("password"),
                    host = o.getString("host"),
                    realm = o.getString("realm"),
                    port = o.optInt("port", 5060),
                    transport = o.optString("transport", "udp"),
                )

                Log.d(
                    "Hardline",
                    "sip register requested: ${bundle.username}@${bundle.host}:${bundle.port}; transport=${bundle.transport}"
                )

                emitToUi("""{"type":"sip","state":"register_requested"}""")

                if (bundle.transport.lowercase() != "udp") {
                    emitToUi("""{"type":"sip","state":"failed","reason":"transport_must_be_udp"}""")
                    return
                }

                sip.register(bundle)
            } catch (e: Exception) {
                Log.e("Hardline", "register parse failed: ${e.message}")
                throw e
            }
        }

        @JavascriptInterface
        fun call(number: String) {
            Log.d("Hardline", "call: $number")
            emitToUi("""{"type":"call_state","state":"calling","to":${jsonStr(number)}}""")
        }

        @JavascriptInterface
        fun hangup() {
            Log.d("Hardline", "hangup")
            emitToUi("""{"type":"call_state","state":"ended"}""")
        }

        @JavascriptInterface
        fun answer() {
            Log.d("Hardline", "answer")
            emitToUi("""{"type":"call_state","state":"active"}""")
        }

        @JavascriptInterface
        fun setMute(mute: Boolean) {
            Log.d("Hardline", "mute: $mute")
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
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
