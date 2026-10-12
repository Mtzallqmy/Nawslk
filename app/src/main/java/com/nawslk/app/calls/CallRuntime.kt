package com.nawslk.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.nawslk.app.MainActivity
import com.nawslk.app.SecureTokenStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.UUID

/** Process-wide single call owner. The service retains it across UI recreation. */
class CallRuntime private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tokens = SecureTokenStore(context)
    val api = SignalingClient({ tokens.load() })
    private val mutable = MutableStateFlow<CallSession?>(null)
    val current: StateFlow<CallSession?> = mutable
    val error = MutableStateFlow<String?>(null)
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val history = MutableStateFlow(CallHistoryStore(context).read())
    private var engine: WebRtcEngine? = null
    private var audio: CallAudioController? = null
    private var job: Job? = null
    private var actionJob: Job? = null
    private var monitor: Job? = null
    private var sending: Job? = null
    private var outgoing: Channel<Pair<String,JSONObject>>? = null
    private var busy = false
    private var deviceId: String? = null
    private var connectedAt: Long? = null
    private var lastHeartbeat = 0L

    fun watchIncoming() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                if (tokens.load() != null && current.value == null && !busy) {
                    try {
                        deviceId = api.request("GET","/v1/me").getString("deviceId")
                        val sessions = api.request("GET","/v1/calls/incoming").getJSONArray("sessions")
                        if (sessions.length() > 0) {
                            val s = sessions.getJSONObject(0)
                            mutable.value = CallSession(s.getString("id"),s.getString("caller_id"),CallDirection.INCOMING,CallTransport.WEBRTC,CallStatus.RINGING,System.currentTimeMillis())
                            notifyIncoming()
                            startPolling(false)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Offline/local owner stays usable. */ }
                }
                delay(2500)
            }
        }
    }
    fun stopWatching() { monitor?.cancel(); monitor = null }
    private fun notifyIncoming() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("incoming_calls","المكالمات الواردة",NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context,2,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { manager.notify(301,NotificationCompat.Builder(context,"incoming_calls").setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("مكالمة واردة في نوصلك").setContentText("افتح التطبيق للرد أو الرفض").setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(open).setAutoCancel(true).build()) }
        catch (_: SecurityException) { /* Android 13 notification permission was denied. */ }
    }
    fun dial(peer: String) {
        if (busy || current.value != null) return
        busy = true; error.value = null
        scope.launch {
            try {
                val s = api.createSession(UUID.fromString(peer.trim()).toString())
                mutable.value = CallSession(s.getString("id"),peer.trim(),CallDirection.OUTGOING,CallTransport.WEBRTC,CallStatus.RINGING,System.currentTimeMillis())
                startPolling(true)
            } catch (_: Exception) { error.value = "تعذر بدء الاتصال. تحقق من ربط الجهاز ومعرف الطرف الآخر." }
            finally { busy = false }
        }
    }
    fun accept() {
        val s = current.value ?: return
        if (s.direction != CallDirection.INCOMING || s.status != CallStatus.RINGING || busy) return
        busy = true
        actionJob = scope.launch {
            try {
                prepareMedia(s.id)
                api.request("POST","/v1/calls/sessions/${s.id}/accept")
                mutable.value = s.copy(status = CallStatus.CONNECTING)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish(CallStatus.FAILED,"تعذر الرد. تحقق من إذن الميكروفون وإعداد TURN.") }
            finally { busy = false }
        }
    }
    fun reject() {
        val s = current.value ?: return
        if (busy) return
        busy = true
        actionJob = scope.launch {
            try { api.request("POST","/v1/calls/sessions/${s.id}/reject"); finish(CallStatus.REJECTED,null) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { finish(CallStatus.REJECTED,null) }
            finally { busy=false }
        }
    }
    private suspend fun prepareMedia(id: String) {
        val data = api.request("GET","/v1/calls/ice-config").getJSONArray("iceServers")
        val servers = (0 until data.length()).map { i ->
            val o=data.getJSONObject(i); val urls=o.getJSONArray("urls")
            PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) })
                .setUsername(o.optString("username")).setPassword(o.optString("credential")).createIceServer()
        }
        ContextCompat.startForegroundService(context,Intent(context,ActiveCallService::class.java).putExtra("session_id",id))
        // Wait for the actual service foreground handshake, not an arbitrary delay.
        withTimeout(5000) { ActiveCallService.ready.first { it==id } }
        audio = CallAudioController(context) { scope.launch { finish(CallStatus.FAILED,"توقفت المكالمة بسبب مقاطعة صوتية.") } }
        check(audio!!.activate())
        outgoing = Channel(512)
        sending = scope.launch {
            try {
                for ((kind,payload) in outgoing!!) {
                    val body=JSONObject().put("kind",kind).put("payload",payload).put("messageId",UUID.randomUUID().toString())
                    // Retries reuse the same ID, so a lost response cannot replay SDP.
                    var sent = false
                    for (attempt in 0..2) {
                        try { api.request("POST","/v1/calls/sessions/$id/signals",body); sent = true; break }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { delay(500L*(attempt+1)) }
                    }
                    check(sent)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish(CallStatus.FAILED,"تعذر تبادل بيانات الاتصال.") }
        }
        engine = WebRtcEngine(context,servers,{ ice ->
            val result = outgoing?.trySend("ice" to JSONObject().put("candidate",ice.sdp).put("sdpMid",ice.sdpMid ?: "0").put("sdpMLineIndex",ice.sdpMLineIndex))
            if (result?.isFailure == true) finish(CallStatus.FAILED,"ازدحام بيانات الاتصال.")
        },{ state ->
            when(state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (connectedAt == null) connectedAt = System.currentTimeMillis()
                    mutable.value = mutable.value?.copy(status=CallStatus.ACTIVE)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> mutable.value = mutable.value?.copy(status=CallStatus.CONNECTING)
                PeerConnection.PeerConnectionState.FAILED -> finish(CallStatus.FAILED,"تعذر الاتصال بالشبكة. حاول إجراء مكالمة جديدة.")
                else -> Unit
            }
        })
    }
    private fun startPolling(caller: Boolean) {
        job?.cancel()
        job = scope.launch {
            var cursor=0L
            var failures=0
            var connectingSince=System.currentTimeMillis()
            try {
                while(isActive) {
                    val s=current.value ?: break
                    try {
                        val remote=api.session(s.id)
                        if(remote.optBoolean("expired") || remote.getString("state")=="ended") {
                            finish(if(remote.optString("end_reason")=="rejected") CallStatus.REJECTED else CallStatus.ENDED,null,false); break
                        }
                        if(remote.getString("state")=="accepted") {
                            if(caller && engine==null) {
                                mutable.value=s.copy(status=CallStatus.CONNECTING)
                                prepareMedia(s.id)
                                val offer=engine!!.offer()
                                outgoing!!.send("offer" to JSONObject().put("sdp",offer.description))
                                connectingSince=System.currentTimeMillis()
                            }
                            if(System.currentTimeMillis()-lastHeartbeat>20000) { api.request("POST","/v1/calls/sessions/${s.id}/heartbeat"); lastHeartbeat=System.currentTimeMillis() }
                            val messages=api.signals(s.id,cursor).getJSONArray("messages")
                            for(i in 0 until messages.length()) {
                                val m=messages.getJSONObject(i); val p=m.getJSONObject("payload")
                                val transport=checkNotNull(engine)
                                when(m.getString("kind")) {
                                    "offer" -> { val answer=transport.answer(p.getString("sdp")); outgoing!!.send("answer" to JSONObject().put("sdp",answer.description)) }
                                    "answer" -> transport.remote(SessionDescription.Type.ANSWER,p.getString("sdp"))
                                    "ice" -> transport.ice(IceCandidate(p.getString("sdpMid"),p.getInt("sdpMLineIndex"),p.getString("candidate")))
                                }
                                cursor=m.getLong("id")
                            }
                            if(current.value?.status==CallStatus.ACTIVE) connectingSince=System.currentTimeMillis()
                            else if(System.currentTimeMillis()-connectingSince>45000) { finish(CallStatus.FAILED,"انتهت مهلة الاتصال أو استعادة الشبكة."); break }
                        }
                        failures=0
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if(++failures>=8) { finish(CallStatus.FAILED,"انقطع الاتصال بالخادم."); break }
                    }
                    delay(1000)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish(CallStatus.FAILED,"تعذر إكمال الاتصال.") }
        }
    }
    fun toggleMute() { muted.value=!muted.value; engine?.mute(muted.value) }
    fun toggleSpeaker() { speaker.value=!speaker.value; audio?.speaker(speaker.value) }
    fun routeHeadset() {
        try { if(audio?.headset()==true) speaker.value=false else error.value="لا توجد سماعة اتصال متصلة." }
        catch (_: SecurityException) { error.value="امنح إذن الأجهزة القريبة لاستخدام سماعة Bluetooth." }
    }
    fun hangUp() { scope.launch { finish(CallStatus.ENDED,null) } }
    fun serviceDestroyed(id: String?) { if(engine!=null && current.value?.id==id) scope.launch { finish(CallStatus.FAILED,"توقفت خدمة المكالمة.") } }
    private fun finish(status: CallStatus,message: String?,notifyPeer: Boolean=true) {
        val s=current.value ?: return
        // Release microphone first; signaling shutdown must never hold media resources.
        runCatching { engine?.close() }; engine=null
        runCatching { audio?.release() }; audio=null
        actionJob?.cancel(); actionJob=null
        busy=false
        job?.cancel(); job=null
        outgoing?.close(); outgoing=null
        sending?.cancel(); sending=null
        context.stopService(Intent(context,ActiveCallService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(301)
        val ended=s.copy(status=status,endedAtMs=System.currentTimeMillis(),durationMs=connectedAt?.let { System.currentTimeMillis()-it } ?: 0,failureReason=message)
        history.value=(listOf(ended)+history.value).take(100)
        CallHistoryStore(context).save(history.value)
        mutable.value=null; connectedAt=null; lastHeartbeat=0; muted.value=false; speaker.value=false
        error.value=message
        if(notifyPeer) scope.launch { withTimeoutOrNull(10000) { runCatching { api.end(s.id) } } }
    }
    companion object {
        @Volatile private var instance: CallRuntime?=null
        fun get(context: Context): CallRuntime = instance ?: synchronized(this) { instance ?: CallRuntime(context.applicationContext).also { instance=it } }
    }
}
