package com.nawslk.app.calls

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CallScreen(store: CallHistoryStore) {
    var peer by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(store.read()) }
    val coordinator = remember { CallCoordinator() }
    var activeId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("المكالمات التجريبية", style = MaterialTheme.typography.titleLarge)
        Text("هذا وضع محلي تجريبي، ولا يجري اتصالًا حقيقيًا.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(peer, { peer = it }, label = { Text("جهة الاتصال") }, singleLine = true)
        Button(onClick = {
            val session = coordinator.startDemo(peer)
            history = listOf(session) + history
            activeId = session.id
            store.save(history)
        }, enabled = peer.isNotBlank() && activeId == null) { Text("بدء جلسة تجريبية") }

        if (activeId != null) {
            Button(onClick = {
                history = history.map {
                    if (it.id == activeId) coordinator.hangUp()
                    else it
                }
                store.save(history)
                activeId = null
            }) { Text("إنهاء الجلسة") }
        }
        Text("السجل المحلي", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(history, key = { it.id }) { item ->
                Text("${item.peer} — ${item.status.name} — ${item.transport.name}")
                HorizontalDivider()
            }
        }
    }
}
