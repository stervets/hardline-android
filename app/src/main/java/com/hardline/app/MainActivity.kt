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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()

        web.addJavascriptInterface(HardlineBridge(), "HardlineNative")

        // Вариант 1: грузим Nuxt dev server (пока самый простой)
        web.loadUrl("http://10.0.2.2:3001/") // 10.0.2.2 = localhost твоего ПК в эмуляторе
    }

    inner class HardlineBridge {

        @JavascriptInterface
        fun register(json: String) {
            Log.d("Hardline", "register: $json")
            emitToUi("""{"type":"registered"}""")
        }

        @JavascriptInterface
        fun call(number: String) {
            Log.d("Hardline", "call: $number")
            emitToUi("""{"type":"call_state","state":"calling","to":"$number"}""")
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
                web.evaluateJavascript("window.onHardlineEvent && window.onHardlineEvent($safe)", null)
            }
        }
    }
}
