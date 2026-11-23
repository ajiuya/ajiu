package com.aicode.testing

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import com.aicode.feature.agent.data.local.dao.AgentMessageRewindDeviceTest
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

class RoomVerificationInstrumentation : Instrumentation() {
    private var verificationArguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        verificationArguments = arguments
        start()
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onStart() {
        val listener = VerificationListener()
        var result: Result? = null
        var infrastructureFailure: Throwable? = null
        try {
            val requestedClass = verificationArguments?.get("class")
            require(requestedClass == null || requestedClass == TEST_CLASS) {
                "Only the fixed Room verification test class is allowed"
            }
            check(activeTestContext == null) { "Room verification context is already active" }
            activeTestContext = targetContext
            val core = JUnitCore()
            core.addListener(listener)
            result = core.run(AgentMessageRewindDeviceTest::class.java)
            check(listener.runFinished) { "JUnit did not report completion" }
            check(result.runCount == EXPECTED_TEST_COUNT && listener.startedCount == EXPECTED_TEST_COUNT &&
                listener.finishedCount == EXPECTED_TEST_COUNT
            ) {
                "Expected $EXPECTED_TEST_COUNT tests; run=${result.runCount}, started=${listener.startedCount}, finished=${listener.finishedCount}"
            }
        } catch (failure: Throwable) {
            infrastructureFailure = failure
        } finally {
            activeTestContext = null
            verificationArguments = null
        }
        val runCount = result?.runCount ?: listener.startedCount
        val junitFailureCount = result?.failureCount ?: listener.failures.size
        val failureCount = junitFailureCount + if (infrastructureFailure != null) 1 else 0
        val ignoreCount = result?.ignoreCount ?: listener.ignoredCount
        val assumptionFailureCount = listener.assumptionFailureCount
        val completed = infrastructureFailure == null && result != null && listener.runFinished
        val successful = completed && runCount == EXPECTED_TEST_COUNT && failureCount == 0 &&
            ignoreCount == 0 && assumptionFailureCount == 0 && listener.finishedCount == EXPECTED_TEST_COUNT
        val stream = buildString {
            appendLine("Room verification ${if (successful) "PASSED" else "FAILED"}: $TEST_CLASS")
            appendLine("completed=$completed runCount=$runCount failureCount=$failureCount ignoreCount=$ignoreCount assumptionFailureCount=$assumptionFailureCount finishedCount=${listener.finishedCount}")
            listener.failures.forEach { appendLine(it) }
            listener.assumptions.forEach { appendLine(it) }
            listener.ignoredTests.forEach { appendLine("IGNORED: $it") }
            infrastructureFailure?.let { appendLine(evidence("INFRASTRUCTURE FAILURE", it)) }
        }
        val output = Bundle().apply {
            putBoolean("completed", completed)
            putInt("runCount", runCount)
            putInt("failureCount", failureCount)
            putInt("junitFailureCount", junitFailureCount)
            putInt("ignoreCount", ignoreCount)
            putInt("assumptionFailureCount", assumptionFailureCount)
            putInt("finishedCount", listener.finishedCount)
            putBoolean("infrastructureFailure", infrastructureFailure != null)
            putString("stream", stream)
        }
        finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }

    private class VerificationListener : RunListener() {
        var startedCount = 0
        var finishedCount = 0
        var ignoredCount = 0
        var assumptionFailureCount = 0
        var runFinished = false
        val failures = mutableListOf<String>()
        val assumptions = mutableListOf<String>()
        val ignoredTests = mutableListOf<String>()

        override fun testStarted(description: Description) {
            startedCount++
        }

        override fun testFinished(description: Description) {
            finishedCount++
        }

        override fun testFailure(failure: Failure) {
            failures += evidence("FAILURE: ${failure.description.displayName}", failure.exception)
        }

        override fun testAssumptionFailure(failure: Failure) {
            assumptionFailureCount++
            assumptions += evidence("ASSUMPTION FAILURE: ${failure.description.displayName}", failure.exception)
        }

        override fun testIgnored(description: Description) {
            ignoredCount++
            ignoredTests += description.displayName
        }

        override fun testRunFinished(result: Result) {
            runFinished = true
        }
    }

    companion object {
        private const val TEST_CLASS = "com.aicode.feature.agent.data.local.dao.AgentMessageRewindDeviceTest"
        private const val EXPECTED_TEST_COUNT = 15
        @Volatile
        private var activeTestContext: Context? = null

        fun requireTestContext(): Context =
            checkNotNull(activeTestContext) { "Room verification context is only available during the fixed test run" }

        private fun evidence(label: String, failure: Throwable): String = buildString {
            appendLine(label)
            var current: Throwable? = failure
            repeat(4) {
                val cause = current ?: return@buildString
                appendLine("${cause.javaClass.name}: ${cause.message.orEmpty().take(2_048)}")
                cause.stackTrace.take(16).forEach { appendLine("    at $it") }
                current = cause.cause
                if (current != null) appendLine("Caused by:")
            }
        }
    }
}
