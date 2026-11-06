package com.amazon.connect.touchpoint.chat.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amazon.connect.touchpoint.core.TouchpointException
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvent
import com.amazon.connect.touchpoint.core.TouchpointSecurityEvents
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A guide's page goes nowhere, on a real WebView: the instance's own pages, a sibling tenant on the
 * same suffix, anywhere else — each refused, reported, and told to the host without the URL.
 */
@RunWith(AndroidJUnit4::class)
class GuideNavigationTest {
    private class Request(private val target: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(target)

        override fun isForMainFrame(): Boolean = true

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = true

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun everyNavigationIsRefusedAndReportedWithoutItsUrl() =
        runBlocking {
            val targets = listOf("https://acme.awsapps.com/connect/home", "https://other.awsapps.com/", "https://other.my.connect.aws/", "https://evil.com/")
            val rejected = mutableListOf<TouchpointException>()
            val client = GuideWebViewClient(ConnectViewPage.scriptUrl("https://acme.awsapps.com"), { rejected += it }, {})
            val reported =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) { TouchpointSecurityEvents.events.filter { it == TouchpointSecurityEvent.GuideNavigationBlocked }.take(targets.size).toList() }
                }
            val answers = mutableListOf<Boolean>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val web = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
                targets.forEach { answers += client.shouldOverrideUrlLoading(web, Request(it)) }
                web.destroy()
            }
            assertEquals(List(targets.size) { true }, answers)
            assertEquals(targets.size, reported.await().size)
            assertEquals(targets.size, rejected.size)
            assertTrue(rejected.none { e -> targets.any { it in e.toString() || it in e.message.orEmpty() } })
        }
}
