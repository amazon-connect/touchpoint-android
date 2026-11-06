package com.amazon.connect.touchpoint.chat.ui

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.ui.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Web's 12 languages resolve on a device as web resolves them (useCopy.ts `defaultCopy`). */
@RunWith(AndroidJUnit4::class)
class LocalesTest {
    private fun send(tag: String): String {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config).getString(R.string.touchpoint_composer_send)
    }

    @Test
    fun eachOfWebsLanguagesResolvesAndAnythingElseFallsBackToEnglish() {
        assertEquals("Send message", send("en-US"))
        assertEquals("Enviar mensaje", send("es-MX"))
        assertEquals("Nachricht senden", send("de-DE"))
        assertEquals("メッセージを送信", send("ja-JP"))
        // Indonesian: Android's resources are values-in, whichever code the device reports.
        assertEquals("Kirim pesan", send("id-ID"))
        // Traditional Chinese for Taiwan and Hong Kong, as web maps zh-Hant; Simplified otherwise.
        assertEquals("傳送訊息", send("zh-TW"))
        assertEquals("傳送訊息", send("zh-Hant-HK"))
        assertEquals("发送消息", send("zh-Hans-SG"))
        assertEquals("Send message", send("nl-NL"))
    }
}
