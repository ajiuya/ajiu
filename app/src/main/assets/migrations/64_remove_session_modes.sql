CREATE TABLE chat_sessions_new (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    workspacePath TEXT NOT NULL,
    goal TEXT,
    goalStatus TEXT,
    goalTurnCount INTEGER NOT NULL,
    goalStartInputTokens INTEGER NOT NULL,
    goalStartOutputTokens INTEGER NOT NULL,
    reasoningEffort TEXT NOT NULL,
    providerId TEXT,
    model TEXT,
    totalInputTokens INTEGER NOT NULL,
    totalOutputTokens INTEGER NOT NULL,
    lastInputTokens INTEGER NOT NULL,
    isPinned INTEGER NOT NULL,
    parentId TEXT,
    subagentType TEXT
);

INSERT INTO chat_sessions_new (
    id,
    title,
    createdAt,
    updatedAt,
    workspacePath,
    goal,
    goalStatus,
    goalTurnCount,
    goalStartInputTokens,
    goalStartOutputTokens,
    reasoningEffort,
    providerId,
    model,
    totalInputTokens,
    totalOutputTokens,
    lastInputTokens,
    isPinned,
    parentId,
    subagentType
)
SELECT
    id,
    title,
    createdAt,
    updatedAt,
    workspacePath,
    goal,
    goalStatus,
    goalTurnCount,
    goalStartInputTokens,
    goalStartOutputTokens,
    reasoningEffort,
    providerId,
    model,
    totalInputTokens,
    totalOutputTokens,
    lastInputTokens,
    isPinned,
    parentId,
    subagentType
FROM chat_sessions;

DROP TABLE chat_sessions;
ALTER TABLE chat_sessions_new RENAME TO chat_sessions;
CREATE INDEX IF NOT EXISTS index_chat_sessions_workspacePath ON chat_sessions(workspacePath);
