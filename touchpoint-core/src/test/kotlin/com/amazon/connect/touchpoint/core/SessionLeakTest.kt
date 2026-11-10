package com.amazon.connect.touchpoint.core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLeakTest {
    @Test fun `a collector cancelled as its registration returns does not leak its slot`() = runBlocking {
        val fx = SessionFixture()
        val session = fx.session(mapOf(Modality.CHAT to StubTransport()))
        val sched = TestCoroutineScheduler()
        val d = StandardTestDispatcher(sched)
        val job = CoroutineScope(d).launch { session.events.collect { } }
        sched.runCurrent() // collector enters withContext(confined)
        Thread.sleep(200) // registration block runs on the confined thread; resume is queued on d
        job.cancel()
        sched.runCurrent() // resume observes cancellation: awaitClose is never reached
        Thread.sleep(100)
        val f = TouchpointSession::class.java.getDeclaredField("subscribers").apply { isAccessible = true }
        val size = (f.get(session) as Map<*, *>).size
        fx.close()
        assertEquals(0, size)
    }
}
