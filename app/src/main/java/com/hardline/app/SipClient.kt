package com.hardline.app

import android.util.Log
import org.pjsip.pjsua2.*

data class SipBundle(
    val username: String,
    val password: String,
    val host: String,
    val realm: String,
    val port: Int = 5060,
    val transport: String = "udp",
)

class SipClient(
    private val emit: (payloadJson: String) -> Unit,
) {
    private var started = false
    private var ep: Endpoint? = null
    private var account: HardlineAccount? = null

    fun register(sip: SipBundle) {
        Log.d("Hardline", "pjsip register(): enter")

        ensureStarted()
        Log.d("Hardline", "pjsip register(): after ensureStarted")

        emit("""{"type":"sip","state":"registering"}""")

        val acfg = AccountConfig().also {
            it.idUri = "sip:${sip.username}@${sip.realm}"
            it.regConfig.registrarUri = "sip:${sip.host}:${sip.port}"

            val cred = AuthCredInfo("digest", "*", sip.username, 0, sip.password)
            it.sipConfig.authCreds.clear()
            it.sipConfig.authCreds.add(cred)
        }

        Log.d("Hardline", "pjsip register(): cfg idUri=${acfg.idUri} registrar=${acfg.regConfig.registrarUri}")

        try {
            account?.delete()
            Log.d("Hardline", "pjsip register(): old account deleted")

            account = HardlineAccount(emit)
            Log.d("Hardline", "pjsip register(): new account instance created")

            Log.d(
                "Hardline",
                "SIP CONFIG host=${sip.host} realm=${sip.realm} port=${sip.port}"
            )

            account!!.create(acfg)
            Log.d("Hardline", "pjsip register(): account.create() done")

            // важно: явный рег-триггер (на некоторых биндингах create не инициирует регу сразу)
            //account!!.setRegistration(true)
            //Log.d("Hardline", "pjsip register(): setRegistration(true) done")

        } catch (e: Exception) {
            Log.e("Hardline", "pjsip register(): FAILED: ${e.message}")
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
        // локальный порт. 0 = выбрать свободный
        val id = ep!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg)
        Log.d("Hardline", "pjsip udp transport created: id=$id localPort=${tcfg.port}")

        tcfg.port = 5062

        ep!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg)
        ep!!.libStart()

        started = true
        Log.d("Hardline", "pjsip started (udp transport created)")
    }

    private class HardlineAccount(
        private val emit: (payloadJson: String) -> Unit,
    ) : Account() {

        override fun onRegState(prm: OnRegStateParam?) {
            try {
                val ai = this.info
                val active = ai.regIsActive
                val status = ai.regStatus

                Log.d("Hardline", "SIP reg state: active=$active status=$status")

                // Важно: 401 на первом REGISTER — нормальный challenge
                when {
                    active && status == 200 -> {
                        emit("""{"type":"sip","state":"registered","status":200}""")
                    }

                    status >= 300 && status != 401 -> {
                        emit("""{"type":"sip","state":"failed","status":$status}""")
                    }

                    else -> {
                        // промежуточные состояния можно не шуметь
                        emit("""{"type":"sip","state":"progress","active":$active,"status":$status}""")
                    }
                }
            } catch (e: Exception) {
                Log.e("Hardline", "onRegState error: ${e.message}")
                emit("""{"type":"sip","state":"failed","reason":${jsonStr(e.message ?: "onRegState_error")}}""")
            }
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""