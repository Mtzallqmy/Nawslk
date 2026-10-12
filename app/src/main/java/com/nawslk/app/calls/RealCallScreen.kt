package com.nawslk.app.calls

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nawslk.app.SecureTokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
fun RealCallScreen() {
    val context=LocalContext.current
    val runtime=remember { CallRuntime.get(context) }
    val scope=rememberCoroutineScope()
    val call by runtime.current.collectAsState()
    val error by runtime.error.collectAsState()
    val muted by runtime.muted.collectAsState()
    val speaker by runtime.speaker.collectAsState()
    val history by runtime.history.collectAsState()
    var peer by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var pushReady by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var ownId by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var connected by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var elapsed by remember { mutableLongStateOf(0) }
    val notificationsPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted) message="الإشعارات غير مسموحة؛ يمكن الرد داخل شاشة الاتصال."
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.RECORD_AUDIO]==true) pending?.invoke()
        else message="يلزم إذن الميكروفون لإجراء المكالمة."
        pending=null
    }
    val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) runtime.routeHeadset() else message="يلزم إذن الأجهزة القريبة لاستخدام Bluetooth."
    }
    fun authorized(action: () -> Unit) {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) action()
        else {
            pending=action
            permission.launch(if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }
    suspend fun refresh() {
        try {
            val profile=runtime.api.request("GET","/v1/me")
            ownId=profile.getString("deviceId"); isOwner=profile.getBoolean("isOwner"); connected=true
            val registered=runtime.api.request("GET","/v1/devices").getJSONArray("devices")
            devices=(0 until registered.length()).map { val d=registered.getJSONObject(it); d.getString("id") to d.getString("label") }
            runtime.watchIncoming()
            pushReady=try { CallPush.register(context,runtime.api) } catch(_:Exception){false}
        } catch (_: Exception) { connected=false; message="اربط المالك من حسابي أو استخدم دعوة جهاز موثوق." }
    }
    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) { onDispose { runtime.stopWatching() } }
    LaunchedEffect(call?.id) {
        while(call!=null) { elapsed=call?.let { System.currentTimeMillis()-it.startedAtMs } ?: 0; delay(1000) }
    }
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("المكالمات الصوتية الداخلية",style=MaterialTheme.typography.titleLarge)
            Text("WebRTC بين أجهزة موثوقة. لا يدعم الاتصال بأرقام الهاتف العادية.")
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                OutlinedButton(onClick={notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}) { Text("السماح بإشعارات المكالمات") }
            }
            if(connected) {
                Text("معرف جهازك: $ownId",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(peer,{peer=it},label={Text("معرف جهاز الطرف الآخر")},singleLine=true)
                Button(onClick={authorized { runtime.dial(peer) }},enabled=call==null && peer.isNotBlank()) { Text("اتصال صوتي") }
            }
            if(!connected || isOwner) {
                OutlinedTextField(invite,{invite=it},label={Text("دعوة جهاز موثوق")},singleLine=true)
                if(isOwner) Button(onClick={scope.launch { message=try { invite=runtime.api.request("POST","/v1/devices/invites").getString("invite"); "شارك الدعوة مع جهازك الثاني. تنتهي بعد 10 دقائق." } catch(_:Exception){"تعذر إنشاء الدعوة."} }}) { Text("إنشاء دعوة") }
                if(!connected) Button(onClick={scope.launch {
                    try {
                        val result=runtime.api.request("POST","/v1/devices/redeem",JSONObject().put("invite",invite).put("label",Build.MODEL))
                        SecureTokenStore(context).save(result.getString("token")); invite=""; refresh()
                    }catch(_:Exception){message="دعوة غير صالحة أو منتهية، أو الخادم غير متاح."}
                }},enabled=invite.isNotBlank()) { Text("ربط هذا الجهاز بالدعوة") }
            }
            if(isOwner) devices.filter { it.first!=ownId }.forEach { device ->
                Text("${device.second}: ${device.first}",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={scope.launch {
                    try { runtime.api.request("DELETE","/v1/devices/${device.first}"); refresh(); message="تم إبطال الجهاز ومكالماته." }
                    catch(_:Exception){message="تعذر إبطال الجهاز."}
                }}) { Text("إبطال الجهاز") }
            }
            call?.let { s ->
                Text("الطرف: ${s.peer}")
                Text(when(s.status){CallStatus.RINGING -> if(s.direction==CallDirection.INCOMING) "مكالمة واردة" else "جارٍ انتظار الرد"; CallStatus.CONNECTING -> "جارٍ الاتصال أو استعادة الشبكة"; CallStatus.ACTIVE -> "مكالمة نشطة"; else -> "انتهت المكالمة"})
                Text("الوقت منذ بدء الجلسة: ${elapsed/60000}:${(elapsed/1000%60).toString().padStart(2,'0')}")
                if(s.direction==CallDirection.INCOMING && s.status==CallStatus.RINGING) {
                    Row { Button(onClick={authorized { runtime.accept() }}){Text("رد")}; OutlinedButton(onClick={runtime.reject()}){Text("رفض")} }
                } else {
                    Row { Button(onClick={runtime.toggleMute()},enabled=s.status==CallStatus.ACTIVE){Text(if(muted) "إلغاء الكتم" else "كتم")}; OutlinedButton(onClick={runtime.toggleSpeaker()},enabled=s.status==CallStatus.ACTIVE){Text(if(speaker) "سماعة الهاتف" else "مكبر الصوت")} }
                }
                if(s.status==CallStatus.ACTIVE) OutlinedButton(onClick={
                    if(Build.VERSION.SDK_INT>=31 && ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else runtime.routeHeadset()
                }) { Text("سماعة متصلة / Bluetooth") }
                Button(onClick={runtime.hangUp()}){Text("إنهاء")}
            }
            if(message.isNotEmpty()) Text(message)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Text(if(pushReady) "إشعارات الوارد مهيأة عبر Firebase. قد يمنع Android وصولها عند فرض إيقاف التطبيق أو تقييد البطارية. الرد يفتح التطبيق ويطلب إذن الميكروفون." else "Firebase غير مهيأ أو غير متاح؛ استقبال الوارد أثناء فتح شاشة الاتصال فقط.",style=MaterialTheme.typography.bodySmall)
            Text("السجل المحلي",style=MaterialTheme.typography.titleMedium)
        }
        items(history.filter { it.transport==CallTransport.WEBRTC },key={it.id}) { s ->
            Text("${s.peer} • ${if(s.direction==CallDirection.INCOMING) "واردة" else "صادرة"}")
            Text("${DateFormat.getDateTimeInstance().format(Date(s.startedAtMs))} • ${s.durationMs/1000} ثانية")
            Text(when(s.status){CallStatus.REJECTED->"مرفوضة";CallStatus.FAILED->"فشلت";else->"منتهية"})
            s.failureReason?.let { Text(it) }
            HorizontalDivider()
        }
    }
}
