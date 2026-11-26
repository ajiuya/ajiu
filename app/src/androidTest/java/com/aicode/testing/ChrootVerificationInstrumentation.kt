package com.aicode.testing

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailure
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChrootVerificationInstrumentation : Instrumentation() {
    private var verificationArguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        verificationArguments = arguments
        start()
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onStart() {
        var mode = ChrootVerificationMode.FULL
        var diagnostics = ChrootVerificationEvidence()
        var listener = VerificationListener(diagnostics)
        var result: Result? = null
        var infrastructureFailure: Throwable? = null
        var foreground: VerificationForegroundPage? = null
        var diagnosticsPresent = false
        val evidence = Bundle()
        try {
            val arguments = checkNotNull(verificationArguments)
            mode = ChrootVerificationMode.fromArguments(arguments.keySet().associateWith { arguments.getString(it) })
            diagnostics = ChrootVerificationEvidence(mode.dnsQueryIncluded)
            listener = VerificationListener(diagnostics)
            check(activeTestContext == null && activeEvidence == null && activeDiagnostics == null && activeForeground == null)
            activeTestContext = targetContext
            activeEvidence = evidence
            activeDiagnostics = diagnostics
            foreground = VerificationForegroundPage(diagnostics)
            activeForeground = foreground.gate
            foreground.startPage()
            val core = JUnitCore()
            core.addListener(listener)
            val request = mode.method?.let { Request.method(ChrootRuntimeDeviceTest::class.java, it) }
                ?: Request.aClass(ChrootRuntimeDeviceTest::class.java)
            foreground.gate.requireUsable()
            result = core.run(request)
            check(listener.runFinished && mode.completeCounts(result.runCount, listener.startedCount, listener.finishedCount))
        } catch (failure: Throwable) {
            infrastructureFailure = failure
            diagnostics.recordFailure(ChrootVerificationEvidence.FailureKind.INFRASTRUCTURE,
                ChrootVerificationEvidence.Case.UNKNOWN, failure)
        } finally {
            try {
                foreground?.closePage()
            } catch (failure: Throwable) {
                val primary = infrastructureFailure
                if (primary == null) infrastructureFailure = failure else if (primary !== failure) primary.addSuppressed(failure)
                diagnostics.recordFailure(ChrootVerificationEvidence.FailureKind.INFRASTRUCTURE,
                    ChrootVerificationEvidence.Case.UNKNOWN, failure)
            }
            if (!diagnostics.isAttached() || activeJournal == null) {
                diagnostics.flag(ChrootVerificationEvidence.InfrastructureFlag.EVIDENCE_ABSENT)
            }
            persistDiagnostics()
            diagnosticsPresent = activeJournal != null && diagnosticsIdentity != null
            activeTestContext = null
            activeEvidence = null
            activeDiagnostics = null
            activeForeground = null
            activeJournal = null
            journalIdentity = null
            diagnosticsIdentity = null
            verificationArguments = null
        }
        val runCount = result?.runCount ?: listener.startedCount
        val junitFailures = result?.failureCount ?: listener.failureCount
        val infrastructureFailed = infrastructureFailure != null || diagnostics.hasInfrastructureFailure()
        val failureCount = junitFailures + if (infrastructureFailed) 1 else 0
        val ignored = result?.ignoreCount ?: listener.ignoredCount
        val completed = infrastructureFailure == null && result != null && listener.runFinished
        val successful = completed && mode.completeCounts(runCount, listener.startedCount, listener.finishedCount) &&
            failureCount == 0 && ignored == 0 && listener.assumptionCount == 0 &&
            evidence.getBoolean("rootfsRemoved") && evidence.getBoolean("cleanupVerified")
        val output = Bundle(evidence).apply {
            putBoolean("completed", completed)
            putBoolean("successful", successful)
            putString("verificationMode", mode.argument)
            putInt("expectedTestCount", mode.expectedCount)
            putInt("runCount", runCount)
            putInt("startedCount", listener.startedCount)
            putInt("finishedCount", listener.finishedCount)
            putInt("failureCount", failureCount)
            putInt("junitFailureCount", junitFailures)
            putInt("ignoreCount", ignored)
            putInt("assumptionFailureCount", listener.assumptionCount)
            putBoolean("infrastructureFailure", infrastructureFailed)
            putBoolean("diagnosticsPresent", diagnosticsPresent)
            putBoolean("diagnosticsInfrastructureFailure", diagnostics.hasInfrastructureFailure())
            putBoolean("foregroundEstablished", foreground?.gate?.snapshot()?.established == true)
            putBoolean("foregroundLost", foreground?.gate?.snapshot()?.lost == true)
            putBoolean("foregroundClosed", foreground?.gate?.snapshot()?.closed == true)
            putBoolean("fixedDnsQueryIncluded", diagnostics.dnsQueryIncluded)
            putBoolean("dnsVerified", diagnostics.isDnsVerified())
            putString("dnsObservation", if (diagnostics.isDnsVerified()) "VERIFIED" else "UNOBSERVED")
            putString("diagnostics", diagnostics.json(6 * 1024))
            putString("stream", ChrootVerificationEvidence.boundedReport(buildString {
                appendLine("Chroot production verification [${mode.argument}] ${if (successful) "PASSED" else "FAILED"}")
                appendLine("completed=$completed runCount=$runCount failureCount=$failureCount ignoreCount=$ignored assumptionFailureCount=${listener.assumptionCount}")
                appendLine("App-owned verification artifacts are retained; Root settings were not changed.")
                appendLine(diagnostics.firstFailureReport())
                appendLine("Full bounded evidence: diagnostics.json in the current App-owned verification journal.")
            }))
        }
        finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }

    private inner class VerificationForegroundPage(
        private val diagnostics: ChrootVerificationEvidence
    ) : Application.ActivityLifecycleCallbacks {
        val gate = ChrootVerificationForeground()
        private val ready = CountDownLatch(1)
        private val application = targetContext.applicationContext as Application
        private val component = ComponentName(targetContext.packageName, "com.aicode.testing.ChrootVerificationActivity")
        private var activity: Activity? = null
        private var registered = false
        private var focusObserver: ViewTreeObserver? = null
        private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            try { gate.onWindowFocusChanged(focused) } finally { observeState() }
        }

        fun startPage() {
            try {
                onMain {
                    application.registerActivityLifecycleCallbacks(this)
                    registered = true
                }
                val launched = startActivitySync(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onMain {
                    check(activity === launched)
                    val decor = launched.window.decorView
                    focusObserver = decor.viewTreeObserver.also { it.addOnWindowFocusChangeListener(focusListener) }
                    gate.onWindowFocusChanged(decor.hasWindowFocus())
                    observeState()
                }
                VerificationAssertion.requireTrue(ready.await(5L, TimeUnit.SECONDS), "verification_foreground_required")
                gate.requireUsable()
            } catch (failure: Throwable) {
                diagnostics.flag(ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_NOT_READY)
                throw failure
            }
        }

        fun closePage() {
            gate.beginClosing()
            gate.detachStopCallback()
            try {
                onMain {
                    val current = activity
                    val actions = listOf<() -> Unit>(
                        { if (registered) { application.unregisterActivityLifecycleCallbacks(this); registered = false } },
                        { focusObserver?.let { if (it.isAlive) it.removeOnWindowFocusChangeListener(focusListener) }; focusObserver = null },
                        { current?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) },
                        { current?.finish() }
                    )
                    var primary: Throwable? = null
                    for (action in actions) {
                        try { action() } catch (failure: Throwable) {
                            val previous = primary
                            if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
                        }
                    }
                    primary?.let { throw it }
                }
                gate.markClosed()
            } catch (failure: Throwable) {
                diagnostics.flag(if (gate.snapshot().established) ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_LOST
                    else ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_NOT_READY)
                throw failure
            }
        }

        private fun onMain(action: () -> Unit) {
            var failure: Throwable? = null
            runOnMainSync { try { action() } catch (caught: Throwable) { failure = caught } }
            failure?.let { throw it }
        }

        private fun isTarget(candidate: Activity): Boolean {
            if (activity == null && candidate.componentName == component) activity = candidate
            return activity === candidate
        }

        private fun observeState() {
            val state = gate.snapshot()
            if (state.lost) diagnostics.flag(ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_LOST)
            if (state.usable) ready.countDown()
        }

        private fun update(candidate: Activity, change: () -> Unit) {
            if (isTarget(candidate)) {
                try { change() } finally { observeState() }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { isTarget(activity) }
        override fun onActivityStarted(activity: Activity) = update(activity, gate::onStarted)
        override fun onActivityResumed(activity: Activity) = update(activity, gate::onResumed)
        override fun onActivityPaused(activity: Activity) = update(activity, gate::onPaused)
        override fun onActivityStopped(activity: Activity) = update(activity, gate::onStopped)
        override fun onActivityDestroyed(activity: Activity) = update(activity, gate::onDestroyed)
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    private class VerificationListener(private val diagnostics: ChrootVerificationEvidence) : RunListener() {
        var startedCount = 0
        var finishedCount = 0
        var ignoredCount = 0
        var assumptionCount = 0
        var failureCount = 0
        var runFinished = false

        override fun testStarted(description: Description) { startedCount++ }
        override fun testFinished(description: Description) { finishedCount++; persistDiagnostics() }
        override fun testIgnored(description: Description) { ignoredCount++ }
        override fun testAssumptionFailure(failure: Failure) {
            assumptionCount++
            diagnostics.recordFailure(ChrootVerificationEvidence.FailureKind.ASSUMPTION,
                ChrootVerificationEvidence.Case.fromMethod(failure.description.methodName), failure.exception)
            persistDiagnostics()
        }
        override fun testFailure(failure: Failure) {
            failureCount++
            diagnostics.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE,
                ChrootVerificationEvidence.Case.fromMethod(failure.description.methodName), failure.exception)
            persistDiagnostics()
        }
        override fun testRunFinished(result: Result) { runFinished = true }
    }

    companion object {
        @Volatile private var activeTestContext: Context? = null
        @Volatile private var activeEvidence: Bundle? = null
        @Volatile private var activeDiagnostics: ChrootVerificationEvidence? = null
        @Volatile private var activeForeground: ChrootVerificationForeground? = null
        private var activeJournal: File? = null
        private var journalIdentity: Pair<Long, Long>? = null
        private var diagnosticsIdentity: Pair<Long, Long>? = null
        private val random = SecureRandom()

        fun requireTestContext(): Context = checkNotNull(activeTestContext)

        internal fun requireForeground(): ChrootVerificationForeground =
            checkNotNull(activeForeground) { "verification_foreground_missing" }

        @Synchronized internal fun attachJournal(runId: String, directory: File) {
            val context = requireTestContext()
            require(runId.matches(Regex("[0-9a-f]{24}")))
            require(directory == File(context.noBackupFilesDir, "chroot/device-verification/$runId"))
            check(activeJournal == null)
            checkNotNull(activeDiagnostics).attach(runId)
            val stat = Os.lstat(directory.path)
            check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid())
            journalIdentity = stat.st_dev to stat.st_ino
            activeJournal = directory
            persistDiagnostics()
        }

        @Synchronized internal fun recordHandle(
            launchId: String,
            goObserved: Boolean,
            task: ChrootVerificationEvidence.TaskState,
            cleanup: ChrootVerificationEvidence.CleanupState,
            failure: ChrootRuntimeFailure?,
            primary: Throwable?,
            nativeFailure: ChrootRuntimeFailure? = null
        ) {
            checkNotNull(activeDiagnostics).recordHandle(launchId, goObserved, task, cleanup, failure, primary, nativeFailure)
            persistDiagnostics()
        }

        @Synchronized internal fun recordInfrastructure(flag: ChrootVerificationEvidence.InfrastructureFlag) {
            activeDiagnostics?.flag(flag)
            persistDiagnostics()
        }

        @Synchronized internal fun recordDnsVerified() {
            checkNotNull(activeDiagnostics).markDnsVerified()
            persistDiagnostics()
        }

        @Synchronized internal fun recordEvidence(
            runId: String,
            instanceId: String?,
            handles: Int,
            verified: Int,
            cleanupVerified: Boolean,
            rootfsRemoved: Boolean
        ) {
            require(runId.matches(Regex("[0-9a-f]{24}")))
            require(instanceId == null || instanceId.matches(Regex("[0-9a-f]{24}")))
            val evidence = checkNotNull(activeEvidence)
            checkNotNull(activeDiagnostics).recordSummary(instanceId, handles, verified, cleanupVerified, rootfsRemoved)
            evidence.putString("runId", runId)
            instanceId?.let { evidence.putString("instanceId", it) }
            evidence.putInt("launchCount", handles)
            evidence.putInt("verifiedCleanupCount", verified)
            evidence.putInt("unknownCleanupCount", handles - verified)
            evidence.putBoolean("cleanupVerified", cleanupVerified)
            evidence.putBoolean("rootfsRemoved", rootfsRemoved)
            evidence.putBoolean("appArtifactsRetained", true)
            evidence.putBoolean("rootSettingsChanged", false)
            evidence.putBoolean("fixedDnsQueryIncluded", checkNotNull(activeDiagnostics).dnsQueryIncluded)
            persistDiagnostics()
        }

        @Synchronized private fun persistDiagnostics() {
            val diagnostics = activeDiagnostics ?: return
            val directory = activeJournal ?: return
            try {
                val owner = Os.lstat(directory.path)
                check(OsConstants.S_ISDIR(owner.st_mode) && owner.st_uid == Process.myUid() &&
                    (owner.st_dev to owner.st_ino) == journalIdentity)
                val bytes = diagnostics.json().toByteArray(Charsets.UTF_8)
                check(bytes.size <= ChrootVerificationEvidence.MAX_JSON_BYTES)
                val suffix = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val staged = File(directory, ".diagnostics-$suffix.part")
                val descriptor = Os.open(staged.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                    OsConstants.O_EXCL or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
                val owned: Pair<Long, Long>
                try {
                    val stat = Os.fstat(descriptor)
                    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() && stat.st_nlink == 1L && (stat.st_mode and 511) == 384)
                    owned = stat.st_dev to stat.st_ino
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = Os.write(descriptor, bytes, offset, bytes.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    Os.fsync(descriptor)
                } finally {
                    Os.close(descriptor)
                }
                val target = File(directory, "diagnostics.json")
                val expected = diagnosticsIdentity
                if (expected == null) {
                    check(!Files.exists(target.toPath(), NOFOLLOW_LINKS))
                } else {
                    val previous = Os.lstat(target.path)
                    check(OsConstants.S_ISREG(previous.st_mode) && previous.st_uid == Process.myUid() &&
                        previous.st_nlink == 1L && (previous.st_mode and 511) == 384 && (previous.st_dev to previous.st_ino) == expected)
                }
                val current = Os.lstat(directory.path)
                check((current.st_dev to current.st_ino) == journalIdentity)
                Os.rename(staged.path, target.path)
                diagnosticsIdentity = owned
                val parent = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                try { Os.fsync(parent) } finally { Os.close(parent) }
            } catch (_: Throwable) {
                diagnostics.flag(ChrootVerificationEvidence.InfrastructureFlag.EVIDENCE_WRITE_FAILED)
                activeEvidence?.putBoolean("diagnosticsWriteFailed", true)
            }
        }
    }
}
