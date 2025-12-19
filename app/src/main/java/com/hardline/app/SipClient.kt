package com.hardline.app

import android.util.Log
import org.pjsip.pjsua2.*

data class SipBundle(
    val username: String,
    val password: String,
    val host: String,
    val realm: String,
    val port: Int = 5060,
)

class SipClient(
    private val emit: (payloadJson: String) -> Unit,
) {
    private var started = false
    private var ep: Endpoint? = null
    private var account: HardlineAccount? = null
    private var currentSip: SipBundle? = null
    private var call: HardlineCall? = null

    fun register(sip: SipBundle) {
        ensureStarted()
        currentSip = sip

        emit("""{"type":"sip","state":"registering"}""")

        val acfg = AccountConfig().also {
            it.idUri = "sip:${sip.username}@${sip.realm}"
            it.regConfig.registrarUri = "sip:${sip.host}:${sip.port}"

            val cred = AuthCredInfo("digest", "*", sip.username, 0, sip.password)
            it.sipConfig.authCreds.clear()
            it.sipConfig.authCreds.add(cred)
        }

        try {
            call?.safeDelete()
            call = null

            account?.delete()
            account = HardlineAccount(emit) { onIncomingCall(it) }
            account!!.create(acfg)
        } catch (e: Exception) {
            Log.e("HardlineSIP", "register failed: ${e.message}", e)
            emit("""{"type":"sip","state":"failed","reason":${jsonStr(e.message ?: "error")}}""")
            throw e
        }
    }

    fun call(number: String) {
        val sip = currentSip ?: error("SipBundle not set")
        val acc = account ?: error("Account not created")

        try {
            call?.safeDelete()
            call = HardlineCall(acc, emit)

            val prm = CallOpParam(true)
            // Обычно: user@realm. Host тоже может работать, но держим единообразно с idUri.
            val uri = "sip:$number@${sip.realm}"

            emit("""{"type":"call_state","state":"calling","to":${jsonStr(number)}}""")
            call!!.makeCall(uri, prm)
        } catch (e: Exception) {
            Log.e("HardlineSIP", "call failed: ${e.message}", e)
            emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "call_error")}}""")
            throw e
        }
    }

    fun hangup() {
        try {
            val c = call ?: return
            val prm = CallOpParam()
            prm.statusCode = pjsip_status_code.PJSIP_SC_DECLINE
            c.hangup(prm)
        } catch (e: Exception) {
            Log.e("HardlineSIP", "hangup failed: ${e.message}", e)
            emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "hangup_error")}}""")
            throw e
        }
    }

    fun answer() {
        try {
            val c = call ?: return
            val prm = CallOpParam()
            prm.statusCode = pjsip_status_code.PJSIP_SC_OK
            c.answer(prm)
        } catch (e: Exception) {
            Log.e("HardlineSIP", "answer failed: ${e.message}", e)
            emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "answer_error")}}""")
            throw e
        }
    }

    fun setMute(mute: Boolean) {
        // Аудио пока не подключаем — заглушка под будущее.
        emit("""{"type":"call_state","state":"mute","value":$mute}""")
    }

    private fun onIncomingCall(callId: Int) {
        try {
            call?.safeDelete()
            call = HardlineCall(account!!, emit, callId)

            emit(
                """{"type":"call_state","state":"incoming","from":${jsonStr(call!!.info.remoteUri)}}"""
            )
        } catch (e: Exception) {
            Log.e("HardlineSIP", "incoming failed: ${e.message}", e)
            emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "incoming_error")}}""")
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
        private val onIncoming: (callId: Int) -> Unit,
    ) : Account() {

        override fun onRegState(prm: OnRegStateParam?) {
            try {
                val ai = this.info
                val active = ai.regIsActive
                val status = ai.regStatus

                when {
                    active && status == 200 -> emit("""{"type":"sip","state":"registered","status":200}""")
                    status >= 300 && status != 401 -> emit("""{"type":"sip","state":"failed","status":$status}""")
                    else -> emit("""{"type":"sip","state":"progress","active":$active,"status":$status}""")
                }
            } catch (e: Exception) {
                Log.e("HardlineSIP", "onRegState error: ${e.message}", e)
                emit("""{"type":"sip","state":"failed","reason":${jsonStr(e.message ?: "onRegState_error")}}""")
            }
        }

        override fun onIncomingCall(prm: OnIncomingCallParam?) {
            try {
                val id = prm?.callId ?: return
                onIncoming(id)
            } catch (e: Exception) {
                Log.e("HardlineSIP", "onIncomingCall error: ${e.message}", e)
                emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "onIncomingCall_error")}}""")
            }
        }
    }

    private class HardlineCall(
        acc: Account,
        private val emit: (payloadJson: String) -> Unit,
        callId: Int = -1,
    ) : Call(acc, callId) {

        override fun onCallState(prm: OnCallStateParam?) {
            try {
                val ci = this.info
                val st = ci.state

                when (st) {
                    pjsip_inv_state.PJSIP_INV_STATE_CALLING ->
                        emit("""{"type":"call_state","state":"calling"}""")

                    pjsip_inv_state.PJSIP_INV_STATE_EARLY ->
                        emit("""{"type":"call_state","state":"ringing"}""")

                    pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED ->
                        emit("""{"type":"call_state","state":"active"}""")

                    pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED ->
                        emit("""{"type":"call_state","state":"ended","code":${ci.lastStatusCode}}""")

                    else ->
                        emit("""{"type":"call_state","state":"progress","raw":${jsonStr(ci.stateText)}}""")
                }
            } catch (e: Exception) {
                Log.e("HardlineSIP", "onCallState error: ${e.message}", e)
                emit("""{"type":"call_state","state":"error","reason":${jsonStr(e.message ?: "onCallState_error")}}""")
            }
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam?) {
            // Аудио позже. Сейчас просто подтверждаем что медиа сменилось.
            emit("""{"type":"call_state","state":"media_changed"}""")
        }

        fun safeDelete() {
            try { delete() } catch (_: Exception) {}
        }
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
