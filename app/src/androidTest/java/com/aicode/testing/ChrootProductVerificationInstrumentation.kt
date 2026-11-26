package com.aicode.testing

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.aicode.feature.agent.domain.container.chroot.ChrootBrokerFdDeviceTest
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

class ChrootProductVerificationInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onStart() {
        val trace = ChrootProductTrace(SystemClock::elapsedRealtime)
        trace.phase(ChrootProductTrace.Phase.PREFLIGHT)
        val listener = Counts(trace)
        val infrastructure = mutableListOf<String>()
        var fixture: ChrootProductFixture? = null
        var page: ForegroundPage? = null
        var fdCount = 0
        var productCount = 0
        var validated = false
        var reportRunId: String? = null
        var persisted: Pair<Int, String>? = null
        try {
            val args = checkNotNull(arguments)
            ChrootProductVerificationMode.checkArguments(args.keySet().associateWith { args.getString(it) })
            validated = true
            check(activeFixture == null)
            check(targetContext.packageName == "com.ajiu.debug")
            page = ForegroundPage(trace)
            page.startPage()
            trace.phase(ChrootProductTrace.Phase.FD_SUITE)
            trace.event(ChrootProductTrace.Kind.BEGIN)
            val core = JUnitCore().apply { addListener(listener) }
            val fdResult = core.run(Request.aClass(ChrootBrokerFdDeviceTest::class.java))
            trace.event(ChrootProductTrace.Kind.END)
            fdCount = fdResult.runCount
            check(fdCount == ChrootProductVerificationMode.FD_COUNT && fdResult.wasSuccessful() &&
                fdResult.ignoreCount == 0 && listener.assumptions == 0)
            fixture = ChrootProductFixture(targetContext, page.gate, trace)
            activeFixture = fixture
            page.awaitReady()
            runBlocking { fixture.prepareFromUser() }
            page.arm()
            val productResult = core.run(Request.aClass(ChrootProductRuntimeDeviceTest::class.java))
            productCount = productResult.runCount
            check(productCount == ChrootProductVerificationMode.PRODUCT_COUNT)
        } catch (failure: Throwable) {
            recordFailure(trace, failure)
            infrastructure += failure.javaClass.simpleName
        } finally {
            try { runBlocking { fixture?.finishFromDriver() } }
            catch (failure: Throwable) { recordFailure(trace, failure); infrastructure += "finish_${failure.javaClass.simpleName}" }
            try { page?.closePage() }
            catch (failure: Throwable) { recordFailure(trace, failure); infrastructure += "page_${failure.javaClass.simpleName}" }
            if (page?.gate?.snapshot()?.lost == true) infrastructure += "foreground_lost"
            val product = try { fixture?.let { JSONObject(it.report()) } ?: JSONObject() }
            catch (failure: Throwable) {
                recordFailure(trace, failure)
                infrastructure += "product_evidence_${failure.javaClass.simpleName}"
                JSONObject().put("evidenceUnavailable", true).put("trace", JSONObject(trace.snapshot().json()))
            }
            val full = JSONObject().apply {
                put("schemaVersion", 1)
                put("mode", ChrootProductVerificationMode.MODE)
                put("fdTestCount", fdCount)
                put("productTestCount", productCount)
                put("startedCount", listener.started)
                put("finishedCount", listener.finished)
                put("failureCount", listener.failed)
                put("ignoreCount", listener.ignored)
                put("assumptionCount", listener.assumptions)
                put("failures", listener.failures)
                put("infrastructure", JSONArray(infrastructure))
                put("foregroundEstablished", page?.gate?.snapshot()?.established == true)
                put("foregroundLost", page?.gate?.snapshot()?.lost == true)
                put("foregroundClosed", page?.gate?.snapshot()?.closed == true)
                put("product", product)
                if (fixture == null) put("trace", JSONObject(trace.snapshot().json()))
            }.toString()
            try {
                check(validated)
                reportRunId = fixture?.runId ?: java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                val directory = fixture?.journalDirectory ?: File(targetContext.noBackupFilesDir,
                    "chroot/product-preflight/$reportRunId").also {
                    check(!Files.exists(it.toPath(), NOFOLLOW_LINKS))
                    check(it.mkdirs())
                    Os.chmod(it.path, 0x1c0)
                }
                persisted = persist(directory, full)
            } catch (failure: Throwable) { infrastructure += "evidence_${failure.javaClass.simpleName}" }
            activeFixture = null
            arguments = null
        }
        val gate = page?.gate?.snapshot()
        val successful = infrastructure.isEmpty() && persisted != null && listener.failed == 0 &&
            listener.ignored == 0 && listener.assumptions == 0 &&
            ChrootProductVerificationMode.countsMatch(fdCount, productCount, listener.started, listener.finished) &&
            gate?.established == true && !gate.lost && gate.closed && fixture?.isSuccessful() == true
        val output = Bundle().apply {
            putBoolean("completed", infrastructure.isEmpty() && listener.finished == fdCount + productCount)
            putBoolean("successful", successful)
            putString("verificationMode", ChrootProductVerificationMode.MODE)
            putInt("expectedTestCount", ChrootProductVerificationMode.TOTAL_COUNT)
            putInt("fdTestCount", fdCount)
            putInt("productTestCount", productCount)
            putInt("runCount", fdCount + productCount)
            putInt("startedCount", listener.started)
            putInt("finishedCount", listener.finished)
            putInt("failureCount", listener.failed + if (infrastructure.isEmpty()) 0 else 1)
            putInt("ignoreCount", listener.ignored)
            putInt("assumptionFailureCount", listener.assumptions)
            putBoolean("foregroundEstablished", gate?.established == true)
            putBoolean("foregroundLost", gate?.lost == true)
            putBoolean("foregroundClosed", gate?.closed == true)
            putBoolean("networkUsed", false)
            putBoolean("rootSettingsChanged", false)
            reportRunId?.let { putString("runId", it) }
            persisted?.let { (bytes, digest) -> putInt("diagnosticBytes", bytes); putString("diagnosticSha256", digest) }
            putString("stream", "CHROOT offline product verification ${if (successful) "PASSED" else "FAILED"}\n" +
                "fd=$fdCount product=$productCount started=${listener.started} finished=${listener.finished} " +
                "failures=${listener.failed} skipped=${listener.ignored} assumptions=${listener.assumptions}\n" +
                "infrastructure=${infrastructure.joinToString(",")}\n" +
                "Full bounded evidence remains in this run's App-owned product verification directory.")
        }
        finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }

    private class Counts(private val trace: ChrootProductTrace) : RunListener() {
        var started = 0
        var finished = 0
        var failed = 0
        var ignored = 0
        var assumptions = 0
        val failures = JSONArray()
        override fun testStarted(description: Description) { started++ }
        override fun testFinished(description: Description) { finished++ }
        override fun testIgnored(description: Description) { ignored++ }
        override fun testAssumptionFailure(failure: Failure) { assumptions++; record(failure) }
        override fun testFailure(failure: Failure) { failed++; record(failure) }
        private fun record(failure: Failure) {
            recordFailure(trace, failure.exception)
            if (failures.length() < 16) failures.put(JSONObject().apply {
                put("case", failure.description.methodName.orEmpty().take(128))
                put("type", failure.exception.javaClass.simpleName.take(128))
                put("line", failure.exception.stackTrace.firstOrNull { it.className.startsWith("com.aicode.") }?.lineNumber ?: -1)
            })
        }
    }

    private inner class ForegroundPage(private val trace: ChrootProductTrace) : Application.ActivityLifecycleCallbacks {
        val gate = ChrootVerificationForeground()
        private val application = targetContext.applicationContext as Application
        private val component = ComponentName(targetContext.packageName, "com.aicode.testing.ChrootVerificationActivity")
        private val lock = Any()
        private var wake = CountDownLatch(1)
        private var started = false
        private var resumed = false
        private var focused = false
        private var armed = false
        private var closing = false
        private var closed = false
        private var activity: Activity? = null
        private var observer: ViewTreeObserver? = null
        private val listener = ViewTreeObserver.OnWindowFocusChangeListener { focus ->
            update(if (focus) ChrootProductTrace.Kind.FOCUS_GAINED else ChrootProductTrace.Kind.FOCUS_LOST) { focused = focus }
        }

        fun startPage() {
            trace.phase(ChrootProductTrace.Phase.FOREGROUND)
            trace.event(ChrootProductTrace.Kind.BEGIN)
            onMain { application.registerActivityLifecycleCallbacks(this) }
            val launched = startActivitySync(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMain {
                check(activity === launched)
                observer = launched.window.decorView.viewTreeObserver.also { it.addOnWindowFocusChangeListener(listener) }
                val focus = launched.window.decorView.hasWindowFocus()
                update(if (focus) ChrootProductTrace.Kind.FOCUS_GAINED else ChrootProductTrace.Kind.FOCUS_LOST) { focused = focus }
            }
            awaitReady()
            trace.event(ChrootProductTrace.Kind.END)
        }

        fun awaitReady() {
            val waiting = synchronized(lock) {
                if (started && resumed && focused) return
                CountDownLatch(1).also { wake = it }
            }
            check(waiting.await(5, TimeUnit.SECONDS))
            synchronized(lock) { check(started && resumed && focused) }
        }

        fun arm() {
            awaitReady()
            onMain {
                synchronized(lock) {
                    check(!armed && started && resumed && focused)
                    armed = true
                    trace.event(ChrootProductTrace.Kind.ARMED, stateBits())
                    gate.onStarted()
                    gate.onResumed()
                    gate.onWindowFocusChanged(true)
                }
            }
            gate.requireUsable()
        }

        private fun stateBits(): Int =
            (if (started) ChrootProductTrace.STARTED else 0) or
                (if (resumed) ChrootProductTrace.RESUMED else 0) or
                (if (focused) ChrootProductTrace.FOCUSED else 0) or
                (if (armed) ChrootProductTrace.ARMED else 0) or
                (if (closing) ChrootProductTrace.CLOSING else 0) or
                (if (closed) ChrootProductTrace.CLOSED else 0)

        private fun update(kind: ChrootProductTrace.Kind, change: () -> Unit) = synchronized(lock) {
            change()
            trace.event(kind, stateBits())
            if (armed) {
                if (started) gate.onStarted() else gate.onStopped()
                if (resumed) gate.onResumed() else gate.onPaused()
                gate.onWindowFocusChanged(focused)
            }
            if (started && resumed && focused) wake.countDown()
        }

        fun closePage() {
            synchronized(lock) {
                closing = true
                trace.event(ChrootProductTrace.Kind.CLOSING, stateBits())
                gate.beginClosing()
            }
            gate.detachStopCallback()
            val failure = AtomicReference<Throwable?>()
            onMain {
                for (action in listOf<() -> Unit>(
                    { application.unregisterActivityLifecycleCallbacks(this) },
                    { observer?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(listener) } },
                    { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) },
                    { activity?.finish() }
                )) try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            }
            synchronized(lock) {
                closed = true
                trace.event(ChrootProductTrace.Kind.CLOSED, stateBits())
                gate.markClosed()
            }
            failure.get()?.let { throw it }
        }

        private fun target(activity: Activity): Boolean {
            if (activity.componentName != component) return false
            val existing = this.activity
            if (existing == null) this.activity = activity else check(existing === activity)
            return true
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) { target(activity) }
        override fun onActivityStarted(activity: Activity) { if (target(activity)) update(ChrootProductTrace.Kind.STARTED) { started = true } }
        override fun onActivityResumed(activity: Activity) { if (target(activity)) update(ChrootProductTrace.Kind.RESUMED) { resumed = true } }
        override fun onActivityPaused(activity: Activity) { if (target(activity)) update(ChrootProductTrace.Kind.PAUSED) { resumed = false } }
        override fun onActivityStopped(activity: Activity) { if (target(activity)) update(ChrootProductTrace.Kind.STOPPED) { started = false; resumed = false; focused = false } }
        override fun onActivityDestroyed(activity: Activity) { if (target(activity)) update(ChrootProductTrace.Kind.DESTROYED) { started = false; resumed = false; focused = false } }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    private fun onMain(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        runOnMainSync { try { action() } catch (problem: Throwable) { failure.set(problem) } }
        failure.get()?.let { throw it }
    }

    private fun persist(directory: File, text: String): Pair<Int, String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..16 * 1024)
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val file = File(directory, "product-diagnostics.json")
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)
        try {
            FileOutputStream(fd).use { it.write(bytes); it.flush(); Os.fsync(fd) }
        } finally { Os.close(fd) }
        val dir = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(dir).st_mode))
            Os.fsync(dir)
        } finally { Os.close(dir) }
        check(file.readBytes().contentEquals(bytes))
        return bytes.size to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        private fun recordFailure(trace: ChrootProductTrace, failure: Throwable) {
            val code = when (failure) {
                is ChrootProductAssertion -> ChrootProductTrace.FailureCode.valueOf(failure.code)
                is kotlinx.coroutines.TimeoutCancellationException -> ChrootProductTrace.FailureCode.TIMEOUT
                is java.util.concurrent.CancellationException -> ChrootProductTrace.FailureCode.CANCELLED
                is SecurityException -> ChrootProductTrace.FailureCode.SECURITY
                is java.io.IOException -> ChrootProductTrace.FailureCode.IO
                is AssertionError -> ChrootProductTrace.FailureCode.ASSERTION
                else -> ChrootProductTrace.FailureCode.UNEXPECTED
            }
            trace.failure(code)
        }

        @Volatile private var activeFixture: ChrootProductFixture? = null
        internal fun requireFixture(): ChrootProductFixture = checkNotNull(activeFixture)
    }
}
