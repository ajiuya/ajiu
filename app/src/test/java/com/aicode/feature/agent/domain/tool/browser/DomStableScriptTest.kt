package com.aicode.feature.agent.domain.tool.browser

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomStableScriptTest {
    @Test
    fun installsARequestScopedObserverAndBothQuietAndDeadlineTimers() {
        val script = DomStableScript.install("\"request-1\"", 500, 10_000)
        for (part in listOf(
            "id=\"request-1\",quietMs=500,deadlineMs=10000",
            "window.__bicodeDomWaits",
            "Object.create(null)",
            "registry[id]=function(){finish(false);};",
            "observer=new MutationObserver(changed);",
            "observer.observe(document,{subtree:true,childList:true,characterData:true,attributes:true});",
            "function changed(){clearTimeout(quiet);quiet=setTimeout(function(){finish(true);},quietMs);}",
            "deadline=setTimeout(function(){finish(false);},deadlineMs);",
            "window.addEventListener('pagehide',onHide);",
            "function onHide(){finish(false);}"
        )) assertTrue(part, script.contains(part))
        assertTrue(script.indexOf("deadline=setTimeout") < script.lastIndexOf("changed();"))
    }

    @Test
    fun everyTerminalSignalSharesIdempotentTeardownBeforeBridgeDelivery() {
        val script = DomStableScript.install("\"id\"", 500, 1000)
        val cleanup = listOf(
            "if(done)return;done=true;",
            "if(observer)observer.disconnect();",
            "clearTimeout(quiet);clearTimeout(deadline);",
            "window.removeEventListener('pagehide',onHide);",
            "delete registry[id];"
        )
        for (part in cleanup) {
            assertTrue(part, script.contains(part))
            assertTrue(part, script.indexOf(part) < script.indexOf("__browserBridge__.resolve"))
            assertTrue(part, script.indexOf(part) < script.indexOf("__browserBridge__.reject"))
        }
        assertTrue(script.contains("catch(error){finish(false,error);}"))
        assertTrue(script.contains("JSON.stringify(value)"))
    }

    @Test
    fun doesNotSerializePollOrWaitForCssCompositorAnimations() {
        val script = DomStableScript.install("\"id\"", 500, 1000)
        for (part in listOf(
            "innerHTML", "outerHTML", "getElementsByTagName", "querySelectorAll",
            "requestAnimationFrame", "setInterval", "getAnimations", "getComputedStyle",
            "animationstart", "animationend", "transitionend"
        )) assertFalse(part, script.contains(part))
    }

    @Test
    fun cleanupTargetsOnlyItsRequestAndUsesTheSameNamespace() {
        val first = DomStableScript.cleanup("\"first\"")
        assertTrue(first.contains("window.__bicodeDomWaits"))
        assertTrue(first.contains("if(r&&r[\"first\"])r[\"first\"]();"))
        assertFalse(first.contains("second"))
        assertFalse(first.contains("Object.create"))
        assertFalse(first.contains("MutationObserver"))
    }

    @Test
    fun jsonQuotedIdsRemainLiteralInInstallAndCleanupScripts() {
        val quoted = JsonPrimitive("request\"\\\n\r\t").toString()
        val install = DomStableScript.install(quoted, 500, 1000)
        val cleanup = DomStableScript.cleanup(quoted)
        assertTrue(install.contains("var id=$quoted,"))
        assertTrue(cleanup.contains("if(r&&r[$quoted])r[$quoted]();"))
    }
}
