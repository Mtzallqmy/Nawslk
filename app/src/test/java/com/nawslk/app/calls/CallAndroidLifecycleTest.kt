package com.nawslk.app.calls

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** Real Android framework shadows; never substitutes for microphone/device media acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26,35], application = Application::class)
class CallAndroidLifecycleTest {
    private lateinit var context: Application
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("demo_call_history",Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.RECORD_AUDIO)
    }
    @Test fun incomingNotificationDoesNotStartMicrophoneServiceAndRejectsStaleInvites() {
        val manager=context.getSystemService(NotificationManager::class.java)
        CallPush.showIncoming(context,"invalid-id",System.currentTimeMillis()+60000)
        CallPush.showIncoming(context,UUID.randomUUID().toString(),System.currentTimeMillis()-1000)
        assertEquals(0,manager.activeNotifications.size)
        val id=UUID.randomUUID().toString()
        CallPush.showIncoming(context,id,System.currentTimeMillis()+60000)
        CallPush.showIncoming(context,id,System.currentTimeMillis()+60000)
        assertEquals(1,manager.activeNotifications.size)
        assertEquals(Notification.VISIBILITY_PRIVATE,manager.activeNotifications.single().notification.visibility)
        val open=shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(id,open.getStringExtra("incoming_call"))
        assertNull(shadowOf(context).nextStartedService)
    }
    @Test fun historySurvivesStoreRecreationAndContainsRealCallOutcome() {
        val entry=CallSession(UUID.randomUUID().toString(),"trusted-peer",CallDirection.INCOMING,CallTransport.WEBRTC,CallStatus.FAILED,1000,4000,2000,"انقطاع الشبكة")
        CallHistoryStore(context).save(listOf(entry))
        assertEquals(listOf(entry),CallHistoryStore(context).read())
        context.getSharedPreferences("demo_call_history",Context.MODE_PRIVATE).edit().putString("sessions","corrupt").commit()
        assertTrue(CallHistoryStore(context).read().isEmpty())
    }
    @Test fun foregroundServiceHandshakeIsSessionScopedAndClearedOnDestroy() {
        val id=UUID.randomUUID().toString()
        val controller=Robolectric.buildService(ActiveCallService::class.java).create()
        controller.get().onStartCommand(Intent(context,ActiveCallService::class.java).putExtra("session_id",id),0,1)
        assertEquals(id,ActiveCallService.ready.value)
        controller.destroy()
        assertNull(ActiveCallService.ready.value)
    }
    @Test fun audioFocusReleaseRestoresCommunicationMode() {
        val manager=context.getSystemService(AudioManager::class.java)
        manager.mode=AudioManager.MODE_NORMAL
        val audio=CallAudioController(context)
        assertTrue(audio.activate())
        assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.mode)
        audio.release()
        assertEquals(AudioManager.MODE_NORMAL,manager.mode)
        audio.release()
        assertEquals(AudioManager.MODE_NORMAL,manager.mode)
    }
}
