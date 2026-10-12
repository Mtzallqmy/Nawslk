package com.nawslk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.nawslk.app.calls.CallScreen
import com.nawslk.app.calls.CallHistoryStore

private val ComponentActivity.ownerStore by preferencesDataStore("owner")
private val OWNER = stringPreferencesKey("owner_email")
private val PIN_SALT = stringPreferencesKey("pin_salt")
private val PIN_HASH = stringPreferencesKey("pin_hash")
private val FAILURES = androidx.datastore.preferences.core.intPreferencesKey("pin_failures")
private val LAST_FAILURE = androidx.datastore.preferences.core.longPreferencesKey("pin_last_failure")

private data class OwnerState(val email: String?, val salt: String?, val hash: String?, val failures: Int, val lastFailure: Long)

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.hasExtra("incoming_call")) recreate()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val owner by ownerStore.data.map {
                OwnerState(it[OWNER], it[PIN_SALT], it[PIN_HASH], it[FAILURES] ?: 0, it[LAST_FAILURE] ?: 0L)
            }.collectAsState(initial = null)
            var unlocked by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = if(androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    when {
                        owner == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        owner!!.email == null || owner!!.salt == null || owner!!.hash == null ->
                            OwnerSetup { email, pin ->
                                val salt = OwnerSecurity.newSalt()
                                val hash = OwnerSecurity.derive(pin.toCharArray(), salt)
                                scope.launch {
                                    ownerStore.edit {
                                        it[OWNER] = email.trim()
                                        it[PIN_SALT] = salt
                                        it[PIN_HASH] = hash
                                    }
                                    unlocked = true
                                }
                            }
                        !unlocked -> OwnerUnlock(
                            canAttempt = PinThrottle.canTry(owner!!.failures, owner!!.lastFailure, System.currentTimeMillis()),
                            onUnlock = { pin ->
                                val now = System.currentTimeMillis()
                                if (!PinThrottle.canTry(owner!!.failures, owner!!.lastFailure, now)) {
                                    false
                                } else {
                                    val ok = try {
                                        OwnerSecurity.verify(pin.toCharArray(), owner!!.salt!!, owner!!.hash!!)
                                    } catch (_: IllegalArgumentException) { false }
                                    scope.launch {
                                        ownerStore.edit {
                                            if (ok) {
                                                it[FAILURES] = 0
                                                it[LAST_FAILURE] = 0L
                                            } else {
                                                it[FAILURES] = owner!!.failures + 1
                                                it[LAST_FAILURE] = now
                                            }
                                        }
                                        if (ok) unlocked = true
                                    }
                                    ok
                                }
                            }
                        )
                        else -> Home(owner!!.email!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerSetup(onConfirm: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val validEmail = remember(email) { android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() }
    val validPin = pin.length in 6..12 && pin.all(Char::isDigit) && pin == confirm
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("نوصلك", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("تهيئة المالك — بدون تسجيل دخول أو تأكيد البريد")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(email, { email = it }, label = { Text("بريد المالك") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("رمز PIN (6–12 رقمًا)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(12) }, label = { Text("تأكيد رمز PIN") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onConfirm(email, pin) }, enabled = validEmail && validPin) { Text("اعتماد المالك") }
        Spacer(Modifier.height(12.dp))
        Text("البريد معرّف محلي فقط، وليس تصريحًا للوصول إلى الخادم. احتفظ برمزك؛ الاستعادة غير متاحة بعد.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OwnerUnlock(canAttempt: Boolean, onUnlock: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("فتح نوصلك", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12); error = false },
            label = { Text("رمز PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        if (!canAttempt) Text("محاولات كثيرة. حاول مجددًا بعد دقيقة.", color = MaterialTheme.colorScheme.error)
        if (error && canAttempt) Text("رمز PIN غير صحيح", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { error = !onUnlock(pin); pin = "" }, enabled = canAttempt && pin.length in 6..12) { Text("فتح") }
    }
}

@Composable
private fun Home(email: String) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(if((context as? android.app.Activity)?.intent?.hasExtra("incoming_call")==true) 1 else 0) }
    val tabs = listOf("الرئيسية", "الاتصال", "الرسائل", "الأرقام", "حسابي")
    Scaffold(
        topBar = { Surface(tonalElevation = 3.dp) {
            Text("نوصلك • وضع تجريبي", modifier = Modifier.fillMaxWidth().padding(20.dp),
                style = MaterialTheme.typography.titleLarge)
        } },
        bottomBar = { NavigationBar {
            tabs.forEachIndexed { index, title ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Text("●") }, label = { Text(title) })
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tabs[tab], style = MaterialTheme.typography.headlineMedium)
            when (tab) {
                0 -> Text("مرحبًا بك في نوصلك. المكالمات الداخلية قيد الاختبار؛ خدمات أرقام الهاتف والرسائل في مراحل لاحقة.")
                1 -> {
                    var demo by remember { mutableStateOf(false) }
                    val currentCall by com.nawslk.app.calls.CallRuntime.get(context).current.collectAsState()
                    OutlinedButton(onClick={ demo=!demo },enabled=currentCall==null) { Text(if(demo) "المكالمات الصوتية الداخلية" else "فتح الجلسة المحلية التجريبية") }
                    if(demo) CallScreen(remember { CallHistoryStore(context) }) else com.nawslk.app.calls.RealCallScreen()
                }
                2 -> com.nawslk.app.messaging.MessagesScreen(email)
                3 -> com.nawslk.app.messaging.NumbersScreen()
                else -> SettingsContent(email)
            }
        }
    }
}

@Composable
private fun Dialer() {
    var number by remember { mutableStateOf("") }
    OutlinedTextField(number, { number = it.filter { c -> c.isDigit() || c == '+' } },
        label = { Text("رقم الهاتف") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
    listOf("1","2","3","4","5","6","7","8","9","*","0","#").chunked(3).forEach { group ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.forEach { digit ->
                OutlinedButton(onClick = { number += digit }, modifier = Modifier.weight(1f)) { Text(digit) }
            }
        }
    }
    Button(onClick = {}, enabled = false) { Text("اتصال — يتطلب ربط مزود") }
}

@Composable
private fun SettingsContent(email: String) {
    var status by remember { mutableStateOf("جارٍ فحص اتصال الخادم…") }
    LaunchedEffect(Unit) {
        status = try {
            if (BackendClient().health()) "الخادم وقاعدة البيانات متصلان" else "الخادم غير جاهز"
        } catch (_: Exception) { "لا يتوفر اتصال بالخادم — الميزات المحلية تعمل" }
    }
    Text("المالك: $email")
    Text("حالة Railway: $status")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var secret by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var savedToken by remember { mutableStateOf(SecureTokenStore(context).load()) }
    LaunchedEffect(savedToken) { savedToken?.let { token -> message = try { BackendClient().ownerProfile(token); "تم التحقق من جلسة الجهاز بالخادم." } catch (_: Exception) { "تعذر التحقق من الجلسة؛ قد يكون الخادم غير متاح أو الرمز مبطلًا." } } }
    if (savedToken == null) {
        OutlinedTextField(secret, { secret = it }, label = { Text("سر تهيئة الخادم (مرة واحدة)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = {
            scope.launch {
                message = try {
                    val token = BackendClient().bootstrap(email, secret)
                    SecureTokenStore(context).save(token)
                    savedToken = token
                    secret = ""
                    "تم ربط المالك بالخادم بنجاح"
                } catch (_: Exception) {
                    "تعذر الربط. تحقق من سر التهيئة وإعدادات Railway."
                }
            }
        }, enabled = secret.length >= 32) { Text("ربط الخادم") }
    } else {
        Text("يوجد رمز اتصال بالخادم محفوظ بتشفير Android Keystore.")
    }
    if (message.isNotEmpty()) Text(message)
    Text("الربط يتطلب سر تهيئة خاصًا، ولا يعتمد على البريد وحده.")
}
