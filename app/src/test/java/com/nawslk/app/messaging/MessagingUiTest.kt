package com.nawslk.app.messaging

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessagingUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun offlineMessageRemainsQueuedWithExplicitDemoLabel() {
        val context=RuntimeEnvironment.getApplication()
        val repository=MessagesRepository(MessagesDatabase.get(context),"local:ui-owner",null)
        runBlocking { repository.create("+967700000001","اختبار الواجهة",true) }
        compose.setContent { MaterialTheme { MessagesScreen("ui-owner") } }
        compose.waitUntil(15000){compose.onAllNodesWithText("اختبار الواجهة").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("اختبار الواجهة").performClick()
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("message_draft") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("message_draft").performTextInput("رسالة دون اتصال")
        compose.onNodeWithText("حفظ في قائمة الانتظار").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("في انتظار المزامنة — لم تُرسل").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("في انتظار المزامنة — لم تُرسل").assertExists()
        compose.onNodeWithText("هذه محادثات ومسودات محفوظة. لا يُرسل هذا الوضع رسائل SMS إلى الهاتف.").assertExists()
    }
    @Test fun numbersScreenHasNoInventedAssignedNumber() {
        compose.setContent { MaterialTheme { NumbersScreen() } }
        compose.onNodeWithText("أرقامي").assertExists()
        compose.waitUntil(10000){compose.onAllNodesWithText("تعذر الاتصال بالخادم. لا نعرض أرقامًا غير متحقق منها.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("تعذر الاتصال بالخادم. لا نعرض أرقامًا غير متحقق منها.").assertExists()
    }
}
