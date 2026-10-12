package com.nawslk.app.messaging

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nawslk.app.SecureTokenStore
import com.nawslk.app.calls.SignalingClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
fun MessagesScreen(localOwner: String) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val client=remember { SignalingClient({SecureTokenStore(context).load()}) }
    val scopePrefs=remember {context.getSharedPreferences("message_scope",android.content.Context.MODE_PRIVATE)}
    var account by remember { mutableStateOf(if(SecureTokenStore(context).load()!=null) scopePrefs.getString("ownerId",null) ?: "local:$localOwner" else "local:$localOwner") }
    var online by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { account=client.request("GET","/v1/me").getString("ownerId");scopePrefs.edit().putString("ownerId",account).apply();online=true }
        catch(_:Exception){ notice="وضع محلي دون اتصال. الرسائل تبقى في الانتظار ولا تُرسل SMS." }
    }
    val repository=remember(account,online) { MessagesRepository(MessagesDatabase.get(context),account,if(online) client else null) }
    val conversations by repository.conversations.collectAsState(initial=emptyList())
    var peer by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("الرسائل — وضع تجريبي",style=MaterialTheme.typography.titleLarge)
        Text("هذه محادثات ومسودات محفوظة. لا يُرسل هذا الوضع رسائل SMS إلى الهاتف.",style=MaterialTheme.typography.bodySmall)
        if(notice.isNotBlank()) Text(notice)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={scope.launch {
                working=true
                try {
                    val profile=client.request("GET","/v1/me")
                    val remoteAccount=profile.getString("ownerId")
                    scopePrefs.edit().putString("ownerId",remoteAccount).apply()
                    if(remoteAccount!=account){account=remoteAccount;online=true;selected=null;notice="تم ربط الحساب. المسودات المحلية السابقة محفوظة في نطاقها المحلي."}
                    else { online=true;val sent=MessagesRepository(MessagesDatabase.get(context),account,client).sync();notice="تمت مزامنة $sent رسالة تجريبية. لم تُرسل SMS." }
                }catch(_:Exception){notice="تعذرت المزامنة. السجل والمسودات محفوظان محليًا."}
                finally{working=false}
            }},enabled=!working) { Text(if(working) "جارٍ المزامنة…" else "مزامنة تجريبية") }
            if(selected!=null) OutlinedButton(onClick={selected=null}){Text("المحادثات")}
        }
        if(selected==null) {
            OutlinedTextField(peer,{peer=it.take(16)},label={Text("رقم الوجهة بصيغة دولية +…")},singleLine=true)
            OutlinedTextField(label,{label=it.take(80)},label={Text("اسم المحادثة")},singleLine=true)
            Row { Checkbox(consent,{consent=it});Text("لدي موافقة الطرف على التواصل",Modifier.padding(top=12.dp)) }
            Button(onClick={scope.launch {
                try { selected=repository.create(peer,label,consent).id;peer="";label="";consent=false }
                catch(_:Exception){notice="تحقق من الرقم والموافقة."}
            }},enabled=MessageRules.validDestination(peer) && consent) { Text("محادثة تجريبية جديدة") }
            if(conversations.isEmpty()) Text("لا توجد محادثات. أنشئ محادثة لحفظ مسودة دون اتصال.")
            LazyColumn(Modifier.weight(1f)) {
                items(conversations,key={it.id}) { conversation ->
                    Column(Modifier.fillMaxWidth().clickable {selected=conversation.id}.padding(12.dp)) {
                        Text(conversation.label);Text(conversation.peerE164,style=MaterialTheme.typography.bodySmall)
                        Text(if(conversation.draftText.isNotEmpty()) "مسودة محفوظة" else "محادثة تجريبية")
                    }
                    HorizontalDivider()
                }
            }
        } else {
            ConversationScreen(repository,selected!!,onNotice={notice=it}) { selected=null }
        }
    }
}
@Composable
private fun ColumnScope.ConversationScreen(repository: MessagesRepository,id: String,onNotice: (String)->Unit,onDeleted: ()->Unit) {
    val scope=rememberCoroutineScope()
    var text by remember(id) { mutableStateOf("") }
    var queueing by remember(id) { mutableStateOf(false) }
    var loaded by remember(id) { mutableStateOf(false) }
    var limit by remember(id) { mutableIntStateOf(100) }
    val stream=remember(repository,id,limit){repository.messages(id,limit)}
    val messages by stream.collectAsState(initial=emptyList())
    LaunchedEffect(id) { text=repository.conversation(id)?.draftText ?: "";loaded=true }
    // Persist each edit from its event; no background send happens from a draft effect.
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(messages.size==limit && limit<1000) item { OutlinedButton(onClick={limit+=100}){Text("تحميل رسائل أقدم") } }
        items(messages.asReversed(),key={it.id}) { message ->
            Surface(tonalElevation=2.dp,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(message.body)
                    Text(DateFormat.getDateTimeInstance().format(Date(message.createdAtMs)),style=MaterialTheme.typography.bodySmall)
                    Text(when(MessageStatus.valueOf(message.status)) {
                        MessageStatus.DRAFT -> "مسودة"
                        MessageStatus.QUEUED -> "في انتظار المزامنة — لم تُرسل"
                        MessageStatus.SENDING -> "جارٍ الإرسال التجريبي"
                        MessageStatus.SENT -> "تم الإرسال التجريبي فقط — لا SMS"
                        MessageStatus.DELIVERED -> if(message.mode=="DEMO") "تسليم تجريبي" else "تم التسليم حسب المزود"
                        MessageStatus.FAILED -> "تعذرت المزامنة — لم يُثبت الإرسال"
                        MessageStatus.RECEIVED -> if(message.mode=="DEMO") "واردة تجريبية" else "واردة من المزود"
                    },style=MaterialTheme.typography.bodySmall)
                    if(message.status==MessageStatus.FAILED.name) TextButton(onClick={scope.launch {repository.retry(message.id)}}){Text("إعادة إلى قائمة الانتظار")}
                }
            }
        }
    }
    OutlinedTextField(text,{value ->
        if(value.codePointCount(0,value.length)<=1600) {text=value;scope.launch {repository.draft(id,value)}}
    },label={Text("اكتب رسالة — تُحفظ كمسودة")},modifier=Modifier.fillMaxWidth(),enabled=loaded && !queueing)
    Row {
        Button(onClick={scope.launch {queueing=true;try {repository.queue(id,text);text="";onNotice("حُفظت في الانتظار. اضغط المزامنة للإرسال التجريبي.")}catch(_:Exception){onNotice("تعذر حفظ الرسالة.")}finally{queueing=false}}},enabled=!queueing && MessageRules.validBody(text)){Text("حفظ في قائمة الانتظار")}
        TextButton(onClick={scope.launch {repository.delete(id);onDeleted();onNotice("حُذف السجل المحلي فقط. استخدم حذف الخادم لإزالة نسخته.")}}){Text("حذف محلي")}
    }
}

@Composable
fun NumbersScreen() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val client=remember {SignalingClient({SecureTokenStore(context).load()})}
    var numbers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf("لا توجد أرقام فعلية مفعّلة. يلزم حساب مزود اتصالات معتمد.") }
    var loading by remember { mutableStateOf(false) }
    suspend fun refresh() {
        loading=true
        try {
            val data=client.request("GET","/v1/numbers");val items=data.getJSONArray("numbers")
            numbers=(0 until items.length()).map {items.getJSONObject(it)}
            status=if(numbers.isEmpty()) "المزود غير مفعّل؛ لا توجد أرقام للعرض أو الاستقبال." else "أرقام أعادها الخادم من سجلات مزود مرخّص."
        }catch(_:Exception){status="تعذر الاتصال بالخادم. لا نعرض أرقامًا غير متحقق منها."}
        finally{loading=false}
    }
    LaunchedEffect(Unit){refresh()}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("أرقامي",style=MaterialTheme.typography.titleLarge)
        Text(status)
        OutlinedButton(onClick={scope.launch {refresh()}},enabled=!loading){Text("تحديث")}
        LazyColumn {
            items(numbers,key={it.getString("id")}) {number ->
                Text(number.getString("e164"));Text("الحالة: ${number.getString("status")}")
                Text("الإمكانات: ${number.getJSONArray("capabilities")}")
                if(!number.isNull("expires_at")) Text("الصلاحية: ${number.getString("expires_at")}")
                HorizontalDivider()
            }
        }
    }
}
