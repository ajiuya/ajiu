CREATE TABLE IF NOT EXISTS subagent_interactions (
    id TEXT NOT NULL PRIMARY KEY,
    parentSessionId TEXT NOT NULL,
    subSessionId TEXT NOT NULL,
    kind TEXT NOT NULL,
    content TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    origin TEXT NOT NULL,
    sourceMessageId TEXT,
    title TEXT,
    model TEXT,
    instructionId TEXT
);

CREATE INDEX IF NOT EXISTS index_subagent_interactions_parentSessionId_subSessionId_timestamp_id
    ON subagent_interactions(parentSessionId, subSessionId, timestamp, id);
CREATE INDEX IF NOT EXISTS index_subagent_interactions_parentSessionId_timestamp_id
    ON subagent_interactions(parentSessionId, timestamp, id);
CREATE INDEX IF NOT EXISTS index_subagent_interactions_timestamp_id
    ON subagent_interactions(timestamp, id);
CREATE INDEX IF NOT EXISTS index_subagent_interactions_parentSessionId_subSessionId_sourceMessageId
    ON subagent_interactions(parentSessionId, subSessionId, sourceMessageId);
