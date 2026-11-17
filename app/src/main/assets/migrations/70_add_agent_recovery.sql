ALTER TABLE chat_sessions ADD COLUMN executionGeneration INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN autoDispatchBlockedReason TEXT;
ALTER TABLE agent_messages ADD COLUMN modelInputSnapshotJson TEXT;

CREATE TABLE IF NOT EXISTS agent_recovery_requests (
    id TEXT NOT NULL PRIMARY KEY,
    sessionId TEXT NOT NULL,
    generation INTEGER NOT NULL,
    environment TEXT NOT NULL,
    inputMessageId TEXT NOT NULL,
    queueOrder INTEGER NOT NULL,
    payloadJson TEXT NOT NULL,
    status TEXT NOT NULL,
    activeRunId TEXT,
    continuationCount INTEGER NOT NULL,
    continuationOutputTokens INTEGER NOT NULL,
    continuationChars INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS index_agent_recovery_requests_sessionId_generation_queueOrder
    ON agent_recovery_requests(sessionId, generation, queueOrder);
CREATE UNIQUE INDEX IF NOT EXISTS index_agent_recovery_requests_inputMessageId
    ON agent_recovery_requests(inputMessageId);
CREATE INDEX IF NOT EXISTS index_agent_recovery_requests_sessionId_status
    ON agent_recovery_requests(sessionId, status);

CREATE TABLE IF NOT EXISTS agent_recovery_runs (
    id TEXT NOT NULL PRIMARY KEY,
    requestId TEXT NOT NULL,
    sessionId TEXT NOT NULL,
    generation INTEGER NOT NULL,
    ownerFence TEXT NOT NULL,
    status TEXT NOT NULL,
    step INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    committedStep INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    endedAt INTEGER
);
CREATE INDEX IF NOT EXISTS index_agent_recovery_runs_requestId_createdAt
    ON agent_recovery_runs(requestId, createdAt);
CREATE INDEX IF NOT EXISTS index_agent_recovery_runs_sessionId_status
    ON agent_recovery_runs(sessionId, status);

CREATE TABLE IF NOT EXISTS agent_recovery_tools (
    runId TEXT NOT NULL,
    toolCallId TEXT NOT NULL,
    toolName TEXT NOT NULL,
    step INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    status TEXT NOT NULL,
    assistantMessageId TEXT,
    resultMessageId TEXT,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(runId, toolCallId)
);
CREATE INDEX IF NOT EXISTS index_agent_recovery_tools_runId_status
    ON agent_recovery_tools(runId, status);

CREATE TABLE IF NOT EXISTS agent_recovery_drafts (
    runId TEXT NOT NULL PRIMARY KEY,
    step INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    text TEXT NOT NULL,
    updatedAt INTEGER NOT NULL
);
