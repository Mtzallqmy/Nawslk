package com.nawslk.app.messaging

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nawslk.app.SecureTokenStore
import com.nawslk.app.calls.SignalingClient
import kotlinx.coroutines.launch

@Composable
fun InfobipScreen() {
    val context=LocalContext.current
    val client=remember {SignalingClient({SecureTokenStore(context).load()})}
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("لم يُفحص المزود بعد.")}
    fun label(value: String)=when(value) {
        "ACCESS_CONFIRMED"->"نجحت القراءة";"CONFIRMED"->"تأكدت المصادقة"
        "REJECTED","KEY_REJECTED"->"رُفض المفتاح";"FORBIDDEN"->"الوصول ممنوع: راجع الصلاحيات والخدمة"
        "RATE_LIMITED"->"بلغ حد الطلبات";else->"لم يُتحقق منه"
    }
    suspend fun refresh(check: Boolean) {
        busy=true
        try {
            val data=client.request(if(check)"POST" else "GET",if(check)"/v1/providers/infobip/check" else "/v1/providers/infobip/status")
            val status=if(check)client.request("GET","/v1/providers/infobip/status") else data
            val verify=if(check)data else status.optJSONObject("verification")
            result=if(!status.optBoolean("configured"))"مفتاح Infobip غير موجود أو إعداداته غير صالحة على الخادم."
            else if(verify==null)"الإعدادات موجودة؛ لم يتم اختبار صلاحية المفتاح."
            else buildString {
                append(label(verify.optString("authentication")))
                val channels=verify.optJSONObject("channels")
                append("\nقراءة SMS: "+label(channels?.optJSONObject("smsLogs")?.optString("state") ?: ""))
                append("\nقراءة المكالمات: "+label(channels?.optJSONObject("voiceCalls")?.optString("state") ?: ""))
                append("\nصلاحية الإرسال وتفعيل Voice/WebRTC لم تُثبت بفحص القراءة.")
            }
        }catch(_:Exception){result="تعذر الفحص. تحقق من اتصال الخادم وحساب المالك؛ لم تُرسل رسالة أو تُجرَ مكالمة."}
        finally{busy=false}
    }
    LaunchedEffect(Unit){refresh(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("اختبار Infobip",style=MaterialTheme.typography.titleLarge)
        Text(result)
        OutlinedButton(onClick={scope.launch {refresh(true)}},enabled=!busy){Text("فحص قراءة فقط — دون إرسال")}
        Text("يُجري المالك الفحص من الخادم. لا توجد مفاتيح مزود داخل التطبيق.")
        HorizontalDivider()
        Text("اختبار SMS الحقيقي ينتظر رقمًا موثّقًا وموافقة صريحة؛ الإرسال غير متاح من هذه الشاشة.")
        Text("المكالمات الداخلية تستخدم محرك نوصلك الحالي. SDK ومكالمات Infobip لم تُدمج بعد.")
        Button(onClick={},enabled=false){Text("إرسال SMS — غير متاح هنا")}
    }
}
