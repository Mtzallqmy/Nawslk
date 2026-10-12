package com.nawslk.app.messaging

import kotlinx.coroutines.*

/** Last edit survives leaving a Compose tab; only local Room writes run in this scope. */
object DraftSaver {
    val error=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val pending=mutableMapOf<String,Job>()
    fun save(repository: MessagesRepository,id: String,body: String) {
        val key=repository.accountKey+":"+id
        synchronized(pending) {
            pending.remove(key)?.cancel()
            val job=scope.launch(start=CoroutineStart.LAZY) {
                try { repository.draft(id,body);error.value=null }
                catch(cancelled: CancellationException){throw cancelled}
                catch(_:Exception){error.value="تعذر حفظ آخر تعديل للمسودة. تحقق من مساحة التخزين."}
            }
            pending[key]=job
            job.invokeOnCompletion { synchronized(pending) { if(pending[key]===job) pending.remove(key) } }
            job.start()
        }
    }
    suspend fun flush(repository: MessagesRepository,id: String) {
        synchronized(pending){pending[repository.accountKey+":"+id]}?.join()
    }
}
