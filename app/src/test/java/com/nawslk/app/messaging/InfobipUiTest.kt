package com.nawslk.app.messaging

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26,35],application=Application::class)
class InfobipUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun providerDiagnosticsNeverAuthorizeAnSms() {
        compose.setContent {MaterialTheme {InfobipScreen()}}
        compose.onNodeWithText("اختبار Infobip").assertExists()
        compose.onNodeWithText("إرسال SMS — غير متاح هنا").performScrollTo().assertIsNotEnabled()
    }
}
