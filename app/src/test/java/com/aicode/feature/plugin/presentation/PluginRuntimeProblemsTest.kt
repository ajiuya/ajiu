package com.aicode.feature.plugin.presentation

import com.aicode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginRuntimeProblemsTest {
    private val overlayMethods = listOf(
        "overlay.create", "overlay.update", "overlay.close", "overlay.postMessage", "overlay.self",
        "overlay.setDragRegion", "overlay.moveBy", "overlay.resize", "overlay.setVisibilityPolicy"
    )

    @Test fun overlayFailureCodesUseSpecificActionableResources(): Unit {
        val expected = listOf(
            "OVERLAY_SYSTEM_PERMISSION_MISSING" to R.string.plugin_error_overlay_system_permission,
            "OVERLAY_NOT_DECLARED" to R.string.plugin_error_overlay_not_declared,
            "SURFACE_NOT_FOUND" to R.string.plugin_error_overlay_not_declared,
            "OVERLAY_NOT_FOUND" to R.string.plugin_error_overlay_not_found,
            "OVERLAY_LIMIT" to R.string.plugin_error_overlay_limit,
            "OVERLAY_SERVICE_FAILED" to R.string.plugin_error_overlay_failed,
            "OVERLAY_DENIED" to R.string.plugin_error_denied
        )
        for ((code, messageRes) in expected) {
            assertMapping(code, messageRes)
            assertMapping(code, messageRes, "overlay.create")
            assertMapping(code, messageRes, "host.overlay.create")
        }
    }

    @Test fun actualOverlayMethodsUseOverlayCapabilityGuidance(): Unit {
        for (method in overlayMethods) {
            assertMapping("CAPABILITY_REQUIRED", R.string.plugin_error_overlay_capability_required, method)
        }
    }

    @Test fun hostPrefixedOverlayMethodsUseOverlayCapabilityGuidance(): Unit {
        for (method in overlayMethods) {
            assertMapping("CAPABILITY_REQUIRED", R.string.plugin_error_overlay_capability_required, "host.$method")
        }
    }

    @Test fun absentNonOverlayAndUnknownMethodsUseGenericCapabilityGuidance(): Unit {
        val methods = listOf(
            null, "", "app.info", "fs.read", "host.fs.read", "tools.execute", "overlay", "host.overlay",
            "overlay.unknown", "host.overlay.unknown", "overlay.createExtra", "overlay.CREATE",
            "host.host.overlay.create", "host.host.overlay.update"
        )
        for (method in methods) {
            assertMapping("CAPABILITY_REQUIRED", R.string.plugin_error_capability_required, method)
        }
        assertEquals(PluginProblem("CAPABILITY_REQUIRED", R.string.plugin_error_capability_required),
            runtimeProblem("CAPABILITY_REQUIRED", ""))
    }

    @Test fun existingDeniedCodesKeepTheirOriginalCodesAndDeniedMessage(): Unit {
        for (code in listOf("USER_REJECTED", "SYSTEM_DENIED", "PATH_FORBIDDEN", "OVERLAY_DENIED", "SYSTEM_PROTECTED")) {
            assertMapping(code, R.string.plugin_error_denied)
        }
    }

    @Test fun unsupportedCallsRemainExplicitFailures(): Unit {
        for (code in listOf("METHOD_NOT_SUPPORTED", "TOOL_NOT_ALLOWED", "TOOL_NOT_FOUND", "HANDLER_NOT_FOUND", "RUNTIME_REQUIRED", "PLUGIN_METHOD_NOT_SUPPORTED")) {
            assertMapping(code, R.string.plugin_error_not_supported)
        }
    }

    @Test fun missingRuntimeAndToolCodesKeepEnvironmentGuidance(): Unit {
        for (code in listOf("PLUGIN_RUNTIME_MISSING", "PLUGIN_RUNTIME_UNAVAILABLE", "PLUGIN_DEPENDENCY_TOOL")) {
            assertMapping(code, R.string.plugin_error_runtime_missing)
        }
    }

    @Test fun dependencyReadinessCodesKeepDependencyGuidance(): Unit {
        for (code in listOf("PLUGIN_DEPENDENCIES_REQUIRED", "DEPENDENCIES_NOT_READY")) {
            assertMapping(code, R.string.plugin_error_dependencies)
        }
    }

    @Test fun dependencyConfirmationCodesKeepConfirmationGuidance(): Unit {
        for (code in listOf("PLUGIN_CONFIRMATION_REQUIRED", "PLUGIN_DEPENDENCY_CHANGED")) {
            assertMapping(code, R.string.plugin_error_dependency_confirmation)
        }
    }

    @Test fun staleAndChangedContextCodesKeepContextGuidance(): Unit {
        for (code in listOf("CONTEXT_CHANGED", "CONTEXT_REQUIRED", "STALE_CONTEXT", "STALE_CAPTURE", "CONTENT_CHANGED", "PLUGIN_CAPTURE_MISMATCH")) {
            assertMapping(code, R.string.plugin_error_context)
        }
    }

    @Test fun unknownCodesRemainUnchangedAndUseGenericRuntimeMessage(): Unit {
        for (code in listOf("THIRD_PARTY_FAILURE", "INTERNAL", "capability_required", "", "UNKNOWN_" + "x".repeat(100))) {
            assertMapping(code, R.string.plugin_error_runtime, "overlay.create")
        }
    }

    @Test fun englishTechnicalDetailsRemainAvailableForNativeDiagnostics(): Unit {
        val detail = "Grant this capability in native plugin settings"
        val problem = runtimeProblem("CAPABILITY_REQUIRED", detail, "overlay.create")
        assertEquals("CAPABILITY_REQUIRED", problem.code)
        assertEquals(R.string.plugin_error_overlay_capability_required, problem.messageRes)
        assertEquals(detail, problem.detail)
        val unknown = runtimeProblem("THIRD_PARTY_FAILURE", "Third-party script detail is not translated")
        assertEquals("THIRD_PARTY_FAILURE", unknown.code)
        assertEquals(R.string.plugin_error_runtime, unknown.messageRes)
        assertEquals("Third-party script detail is not translated", unknown.detail)
    }

    @Test fun technicalDetailsFilterAllIsoControlCharacters(): Unit {
        val controls = ((0..31).toList() + (127..159).toList()).map { it.toChar() }.joinToString("")
        val problem = runtimeProblem("OVERLAY_SERVICE_FAILED", "before${controls}after 中文")
        assertEquals("OVERLAY_SERVICE_FAILED", problem.code)
        assertEquals(R.string.plugin_error_overlay_failed, problem.messageRes)
        assertEquals("beforeafter 中文", problem.detail)
        assertFalse(problem.detail.any { it.isISOControl() })
    }

    @Test fun technicalDetailsAtOrBelow2048CharactersRemainUnchanged(): Unit {
        for (detail in listOf("", "Native diagnostic", "x".repeat(2047), "x".repeat(2048))) {
            val problem = runtimeProblem("SYSTEM_DENIED", detail)
            assertEquals("SYSTEM_DENIED", problem.code)
            assertEquals(R.string.plugin_error_denied, problem.messageRes)
            assertEquals(detail, problem.detail)
        }
    }

    @Test fun overlongTechnicalDetailsAreFilteredBeforeThe2048CharacterLimit(): Unit {
        val expected = "x".repeat(2047) + "Y"
        val detail = "\u0000" + "x".repeat(2047) + "\n\t\u001B\u007F\u0085" + "Ytail"
        for ((code, messageRes) in listOf("OVERLAY_LIMIT" to R.string.plugin_error_overlay_limit,
            "THIRD_PARTY_FAILURE" to R.string.plugin_error_runtime)) {
            val problem = runtimeProblem(code, detail)
            assertEquals(code, problem.code)
            assertEquals(messageRes, problem.messageRes)
            assertEquals(2048, problem.detail.length)
            assertEquals(expected, problem.detail)
            assertFalse(problem.detail.any { it.isISOControl() })
            assertFalse(problem.detail.contains("tail"))
        }
    }

    private fun assertMapping(code: String, expectedMessageRes: Int, method: String? = null): Unit {
        val problem = runtimeProblem(code, "", method)
        assertEquals(code, problem.code)
        assertEquals(code, expectedMessageRes, problem.messageRes)
        assertEquals("", problem.detail)
    }
}
