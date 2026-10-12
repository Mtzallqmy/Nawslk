package com.nawslk.app.calls

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nawslk.app.MainActivity
import com.nawslk.app.SecureTokenStore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NawslkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallPush.restore(this)
    }
}
object CallPush {
    fun restore(context: Context) {
        val saved=context.getSharedPreferences("firebase_config",Context.MODE_PRIVATE).getString("config",null) ?: return
        runCatching { initialize(context,JSONObject(saved)) }
    }
    private fun initialize(context: Context,config: JSONObject) {
        if(FirebaseApp.getApps(context).any { it.name==FirebaseApp.DEFAULT_APP_NAME }) return
        FirebaseApp.initializeApp(context.applicationContext,FirebaseOptions.Builder()
            .setApplicationId(config.getString("applicationId"))
            .setApiKey(config.getString("apiKey"))
            .setProjectId(config.getString("projectId"))
            .setGcmSenderId(config.getString("gcmSenderId")).build())
    }
    suspend fun register(context: Context,api: SignalingClient): Boolean {
        val config=try { api.request("GET","/v1/push/config") } catch(cancelled: CancellationException){throw cancelled} catch(_:Exception){return false}
        initialize(context,config)
        // Firebase client identifiers are public metadata; no service-account key is sent here.
        context.getSharedPreferences("firebase_config",Context.MODE_PRIVATE).edit().putString("config",config.toString()).apply()
        val token=suspendCancellableCoroutine<String> { continuation ->
            FirebaseMessaging.getInstance().token.addOnSuccessListener { if(continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Push registration unavailable")) }
        }
        api.request("PUT","/v1/devices/push-token",JSONObject().put("token",token))
        return true
    }
    fun showIncoming(context: Context,sessionId: String,expiresAt: Long) {
        val valid=runCatching { UUID.fromString(sessionId).toString()==sessionId.lowercase() }.getOrDefault(false)
        val remaining=expiresAt-System.currentTimeMillis()
        if(!valid || remaining<=0 || remaining>120000) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("incoming_calls","المكالمات الواردة",NotificationManager.IMPORTANCE_HIGH))
        val intent=Intent(context,MainActivity::class.java).putExtra("incoming_call",sessionId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open=PendingIntent.getActivity(context,sessionId.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { manager.notify(301,NotificationCompat.Builder(context,"incoming_calls")
            .setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("مكالمة واردة في نوصلك")
            .setContentText("افتح التطبيق للرد أو الرفض").setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setTimeoutAfter(remaining).setContentIntent(open).setAutoCancel(true).build()) }
        catch(_:SecurityException) { /* Notification denied; never start microphone from push. */ }
    }
}
class CallMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if(SecureTokenStore(this).load()==null || message.data["type"]!="incoming_call") return
        val id=message.data["sessionId"] ?: return
        val expires=message.data["expiresAt"]?.toLongOrNull() ?: return
        // UUID/expiry notification only. The Activity authenticates and checks live server state.
        CallPush.showIncoming(this,id,expires)
    }
    override fun onNewToken(token: String) {
        val saved=SecureTokenStore(this).load() ?: return
        renewalScope.launch {
            runCatching { withTimeout(8000) { SignalingClient({saved}).request("PUT","/v1/devices/push-token",JSONObject().put("token",token)) } }
        }
    }
    companion object { private val renewalScope=CoroutineScope(SupervisorJob()+Dispatchers.IO) }
}
