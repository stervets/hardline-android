package com.hardline.app

import android.util.Log
import org.pjsip.pjsua2.*

data class SipBundle(
    val username: String,
    val password: String,
    val host: String,
    val realm: String,
    val port: Int = 5060,
    //val transport: String = "udp",
)

class SipClient(
    private val emit: (payloadJson: String) -> Unit,
) {
    private var started = false
    private var ep: Endpoint? = null
    private var account: HardlineAccount? = null

    fun register(sip: SipBundle) {
        ensureStarted()

        emit("""{"type":"sip","state":"registering"}""")

        val acfg = AccountConfig().also {
            it.idUri = "sip:${sip.username}@${sip.realm}"
            it.regConfig.registrarUri = "sip:${sip.host}:${sip.port}"

            val cred = AuthCredInfo("digest", "*", sip.username, 0, sip.password)
            it.sipConfig.authCreds.clear()
            it.sipConfig.authCreds.add(cred)
        }

        try {
            account?.delete()
            account = HardlineAccount(emit)
            account!!.create(acfg)
        } catch (e: Exception) {
            Log.e("HardlineSIP", "register failed: ${e.message}", e)
            emit("""{"type":"sip","state":"failed","reason":${jsonStr(e.message ?: "error")}}""")
            throw e
        }
    }

    private fun ensureStarted() {
        if (started) return

        ep = Endpoint()
        ep!!.libCreate()

        val epCfg = EpConfig()
        ep!!.libInit(epCfg)

        val tcfg = TransportConfig()
        ep!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg)

        ep!!.libStart()
        started = true
    }

    private class HardlineAccount(
        private val emit: (payloadJson: String) -> Unit,
    ) : Account() {

        override fun onRegState(prm: OnRegStateParam?) {
            try {
                val ai = this.info
                val active = ai.regIsActive
                val status = ai.regStatus

                when {
                    active && status == 200 -> {
                        emit("""{"type":"sip","state":"registered","status":200}""")
                    }
                    status >= 300 && status != 401 -> {
                        emit("""{"type":"sip","state":"failed","status":$status}""")
                    }
                    else -> {
                        emit("""{"type":"sip","state":"progress","active":$active,"status":$status}""")
                    }
                }
            } catch (e: Exception) {
                Log.e("HardlineSIP", "onRegState error: ${e.message}", e)
                emit("""{"type":"sip","state":"failed","reason":${jsonStr(e.message ?: "onRegState_error")}}""")
            }
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
