package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UiFileExecutionSourceTest {
    @Test fun retryKeepsTheSameOwnerButAnotherUiAndPathCannotShareIt() {
        val scope = Job()
        try {
            val source = UiFileExecutionSource("editor", "selection:path", scope)
            val owner = source.runtimeOwner
            repeat(3) { source.checkCurrent(); assertEquals(owner, source.runtimeOwner) }
            assertEquals(RuntimeOwnerKind.FILE, owner.kind)
            assertNotEquals(owner, UiFileExecutionSource("editor", "selection:path", scope).runtimeOwner)
            assertNotEquals(owner, UiFileExecutionSource("preview", "selection:path", scope).runtimeOwner)
            assertNotEquals(owner, UiFileExecutionSource("editor", "selection:other", scope).runtimeOwner)
        } finally { scope.cancel() }
    }

    @Test fun cancelledScopeClosedSourceAndChangedSelectionCannotBeRevived() {
        val scope = Job()
        var selection = "a"
        val source = UiFileExecutionSource("preview", "a:path", scope) {
            if (selection != "a") throw CancellationException("Selection changed")
        }
        selection = "b"
        assertThrows(CancellationException::class.java) { source.checkCurrent() }
        selection = "a"
        source.close()
        assertThrows(CancellationException::class.java) { source.checkCurrent() }
        val another = UiFileExecutionSource("preview", "a:path", scope)
        scope.cancel()
        assertThrows(CancellationException::class.java) { another.checkCurrent() }
    }
}
