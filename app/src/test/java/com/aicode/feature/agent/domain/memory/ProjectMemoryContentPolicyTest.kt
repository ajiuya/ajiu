package com.aicode.feature.agent.domain.memory

import org.junit.Assert.*
import org.junit.Test

class ProjectMemoryContentPolicyTest {
    @Test fun reportsAcceptFiniteFactsWithoutConvertingThemIntoVerifiedResults() {
        ProjectMemoryContentPolicy.validateReport("Reported changes", "writeFile: reported_success: src/Main.kt\nVerification: unverified; process exit only")
        assertNotNull(ProjectProgressCollector.safeText("Review database migration"))
        assertTrue(ProjectProgressCollector.safePath("app/src/Main.kt"))
    }

    @Test fun sensitiveTodoSubjectsAndFilePathsAreOmittedRatherThanRedactedIntoFacts() {
        listOf("password value", "api_key value", "Bearer value", "https://fixture.invalid", "密钥内容", "a".repeat(32)).forEach {
            assertNull(ProjectProgressCollector.safeText(it))
        }
        listOf(".env", ".ssh/config", "credentials.json", "private_key.pem", "../outside", "src/../file", "/absolute", "name\nfile").forEach {
            assertFalse(ProjectProgressCollector.safePath(it))
        }
    }

    @Test fun credentialsAndPrivateReasoningCannotBeWrittenAsManualHistoryOrCheckpoints() {
        listOf("Authorization: Bearer fixture-value", "api_key=fixture-value", "password: fixture-value", "-----BEGIN PRIVATE KEY-----",
            "privateReasoning: hidden material", "reasoning_content=hidden", "<think>hidden</think>").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProjectMemoryContentPolicy.validateReport("Report", it) }
        }
    }

    @Test fun rawCommandsAndLogsAreRejectedAtTheJournalBoundary() {
        listOf("$ curl fixture.invalid", "./gradlew :app:build", "npm run check", "```bash\ncommand\n```", "stdout: raw output", "stderr: raw output").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProjectMemoryContentPolicy.validateReport("Report", it) }
        }
    }

    @Test fun controlCharactersCannotHideUnboundedOrMultiLineMetadata() {
        assertNull(ProjectProgressCollector.safeText("task\u0000subject"))
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryContentPolicy.validateReport("Two\nlines", "Report") }
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryContentPolicy.validateReport("Report", "body\u0000tail") }
    }
}
