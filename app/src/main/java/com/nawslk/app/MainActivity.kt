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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val ComponentActivity.store by preferencesDataStore("owner")
private val OWNER = stringPreferencesKey("owner_email")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val email by store.data.map { it[OWNER] }.collectAsState(initial = null)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    if (email == null) {
                        OwnerSetup { candidate ->
                            scope.launch { store.edit { it[OWNER] = candidate.trim() } }
                        }
                    } else {
                        Home(email!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerSetup(onConfirm: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    val valid = remember(email) { android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("نوصلك", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("أدخل بريد المالك للتهيئة الشخصية. لا يلزم تأكيد البريد.")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("البريد الإلكتروني") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onConfirm(email) }, enabled = valid) { Text("بدء الاستخدام") }
        Spacer(Modifier.height(8.dp))
        Text("البريد معرّف محلي فقط وليس إثبات ملكية أو تصريحًا للوصول إلى الخادم.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Home(email: String) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("الرئيسية", "الاتصال", "الرسائل", "الأرقام", "حسابي")
    Scaffold(
        topBar = { Surface(tonalElevation = 3.dp) { Text("نوصلك • وضع تجريبي", modifier = Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleLarge) } },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text("●") }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tabs[tab], style = MaterialTheme.typography.headlineMedium)
            when (tab) {
                0 -> Text("مرحبًا بك في نوصلك. المكالمات والرسائل الحقيقية غير مفعّلة بعد.")
                1 -> Dialer()
                2 -> Text("الرسائل: سيتم ربط مزود SMS في مرحلة لاحقة.")
                3 -> Text("الأرقام: ستظهر الأرقام المخصصة فعليًا بعد ربط مزود الاتصالات.")
                else -> Text("المالك: $email\nلا يوجد نظام دفع أو تسجيل دخول.")
            }
        }
    }
}

@Composable
private fun Dialer() {
    var number by remember { mutableStateOf("") }
    OutlinedTextField(
        value = number, onValueChange = { number = it.filter { c -> c.isDigit() || c == '+' } },
        label = { Text("رقم الهاتف") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
    )
    (listOf("1","2","3","4","5","6","7","8","9","*","0","#")).chunked(3).forEach { group ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.forEach { digit ->
                OutlinedButton(onClick = { number += digit }, modifier = Modifier.weight(1f)) { Text(digit) }
            }
        }
    }
    Button(onClick = {}, enabled = false) { Text("اتصال — يتطلب ربط مزود") }
}
