package dev.memoh.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CrashReportStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `worker crash records stack and delegates without storing payloads or thread names`() {
        val store = CrashReportStore(File(folder.root, "last-crash.txt"), "App: test\nAndroid: 16\nDevice: fixture")
        val failure = IllegalStateException("chat content and token=secret", IllegalArgumentException("private input"))
        val delegated = CountDownLatch(1)
        val thread = Thread({ throw failure }, "request https://example.test/?ticket=secret")
        thread.uncaughtExceptionHandler = store.handler(Thread.UncaughtExceptionHandler { owner, error ->
            assertSame(thread, owner); assertSame(failure, error); delegated.countDown()
        }, Thread.currentThread())
        thread.start()
        assertTrue(delegated.await(3, TimeUnit.SECONDS)); thread.join()
        val report = requireNotNull(store.read())
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("CrashReportStoreTest"))
        assertTrue(report.contains("Thread: background"))
        assertFalse(report.contains("secret")); assertFalse(report.contains("private input")); assertFalse(report.contains("chat content"))
    }

    @Test fun `failed report write cannot prevent original crash handler`() {
        val store = CrashReportStore(folder.newFolder("not-a-file"), "test")
        val error = OutOfMemoryError("private allocation detail")
        var delegated = false
        store.handler(Thread.UncaughtExceptionHandler { _, actual ->
            assertSame(error, actual); delegated = true
        }, Thread.currentThread()).uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
    }

    @Test fun `stack overflow and cyclic causes produce bounded reports`() {
        val store = CrashReportStore(File(folder.root, "last-crash.txt"), "test")
        val first = StackOverflowError()
        val second = IllegalStateException()
        first.initCause(second); second.initCause(first)
        first.stackTrace = Array(10_000) { StackTraceElement("Example", "recursiveCall", "Example.kt", it) }
        store.handler(null, Thread.currentThread()).uncaughtException(Thread.currentThread(), first)
        val report = requireNotNull(store.read())
        assertEquals(160, report.lineSequence().count { it.startsWith("    at ") })
        assertTrue(report.length < 16_000)
    }
}
