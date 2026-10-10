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

private val ComponentActivity.ownerStore by preferencesDataStore("owner")
private val OWNER = stringPreferencesKey("owner_email")
private val PIN_SALT = stringPreferencesKey("pin_salt")
private val PIN_HASH = stringPreferencesKey("pin_hash")

private data class OwnerState(val email: String?, val salt: String?, val hash: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val owner by ownerStore.data.map {
                OwnerState(it[OWNER], it[PIN_SALT], it[PIN_HASH])
            }.collectAsState(initial = OwnerState(null, null, null))
            var unlocked by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    when {
                        owner.email == null || owner.salt == null || owner.hash == null ->
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
                            onUnlock = { pin ->
                                try {
                                    val ok = OwnerSecurity.verify(pin.toCharArray(), owner.salt, owner.hash)
                                    if (ok) unlocked = true
                                    ok
                                } catch (_: IllegalArgumentException) { false }
                            }
                        )
                        else -> Home(owner.email)
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
private fun OwnerUnlock(onUnlock: (String) -> Boolean) {
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
        if (error) Text("رمز PIN غير صحيح", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { error = !onUnlock(pin); pin = "" }, enabled = pin.length in 6..12) { Text("فتح") }
    }
}

@Composable
private fun Home(email: String) {
    var tab by remember { mutableIntStateOf(0) }
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
                0 -> Text("مرحبًا بك في نوصلك. المكالمات والرسائل الحقيقية غير مفعّلة بعد.")
                1 -> Dialer()
                2 -> Text("الرسائل: سيتم ربط مزود SMS في مرحلة لاحقة.")
                3 -> Text("الأرقام: ستظهر الأرقام الفعلية بعد ربط مزود الاتصالات.")
                else -> Text("المالك: $email\nلا يوجد نظام دفع أو تسجيل دخول.")
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
