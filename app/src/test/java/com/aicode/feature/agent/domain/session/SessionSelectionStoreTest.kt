package com.aicode.feature.agent.domain.session

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SessionSelectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newStoreHasNoSelection() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(temporary.root, "empty.preferences_pb") }
        assertNull(SessionSelectionStore(data).load("/work"))
    }

    @Test
    fun selectionSurvivesClosingAndReopeningRealDataStore() = runTest {
        val file = File(temporary.root, "reopen.preferences_pb")
        val owner = SupervisorJob()
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))) { file }
        SessionSelectionStore(data).save("chat-local", "/work")
        owner.cancelAndJoin()
        val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        assertEquals("chat-local", SessionSelectionStore(reopened).load("/work"))
    }

    @Test
    fun workspacesKeepIndependentSelectionsAndClearOnlyTheirOwnKey() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(temporary.root, "workspaces.preferences_pb") }
        val store = SessionSelectionStore(data)
        store.save("first", "/work/a")
        store.save("second", "/work/a:子目录")
        assertEquals("first", store.load("/work/a"))
        assertEquals("second", store.load("/work/a:子目录"))
        store.clear("/work/a")
        assertNull(store.load("/work/a"))
        assertEquals("second", store.load("/work/a:子目录"))
    }

    @Test
    fun blankInputsDoNotTouchStorage() = runTest {
        val data = mockk<DataStore<Preferences>>()
        val store = SessionSelectionStore(data)
        assertNull(store.load(" "))
        store.save("", "/work")
        store.save("chat", "")
        store.clear("")
        verify { data wasNot Called }
    }

    @Test
    fun concurrentWorkspaceWritesDoNotLoseOtherSelections() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(temporary.root, "parallel.preferences_pb") }
        val store = SessionSelectionStore(data)
        val first = launch { store.save("a", "/a") }
        val second = launch { store.save("b", "/b") }
        first.join()
        second.join()
        assertEquals("a", store.load("/a"))
        assertEquals("b", store.load("/b"))
    }
}
