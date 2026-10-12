package com.nawslk.app.calls

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nawslk.app.MainActivity

/** Started from a visible Activity only, after RECORD_AUDIO is granted. */
class ActiveCallService : Service() {
    private var sessionId: String? = null
    companion object { val ready = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("active_calls", "المكالمات النشطة", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this,1,Intent(this,ActiveCallService::class.java).setAction("END"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this,"active_calls")
            .setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("مكالمة نوصلك")
            .setContentText("المكالمات الصوتية الداخلية").setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"إنهاء",end).build()
        ServiceCompat.startForeground(this,300,notification,if(Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="END") CallRuntime.get(this).hangUp()
        else { sessionId=intent?.getStringExtra("session_id"); ready.value=sessionId }
        return START_NOT_STICKY
    }
    override fun onDestroy() { ready.value=null; CallRuntime.get(this).serviceDestroyed(sessionId); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
