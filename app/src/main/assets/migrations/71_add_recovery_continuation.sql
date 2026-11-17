ALTER TABLE agent_recovery_runs ADD COLUMN resumeSnapshotJson TEXT;
ALTER TABLE agent_messages ADD COLUMN continuationOfMessageId TEXT;
ALTER TABLE agent_messages ADD COLUMN continuationDraft TEXT;
UPDATE agent_messages
SET continuationOfMessageId = (
    SELECT request.inputMessageId
    FROM agent_recovery_runs AS run
    JOIN agent_recovery_requests AS request ON request.id = run.requestId
    WHERE agent_messages.id = 'resume_' || run.id AND agent_messages.sessionId = run.sessionId
)
WHERE role = 'USER' AND id IN (SELECT 'resume_' || id FROM agent_recovery_runs);
ALTER TABLE subagent_interactions ADD COLUMN generation INTEGER;
ALTER TABLE subagent_interactions ADD COLUMN parentGeneration INTEGER;
ALTER TABLE subagent_interactions ADD COLUMN runId TEXT;
ALTER TABLE subagent_interactions ADD COLUMN agentName TEXT;
CREATE TABLE IF NOT EXISTS agent_notification_outbox (
    seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    generation INTEGER NOT NULL,
    eventKey TEXT NOT NULL,
    payloadJson TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    requiresConfirmation INTEGER NOT NULL DEFAULT 0,
    deliveredAt INTEGER,
    retiredAt INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS index_agent_notification_outbox_eventKey ON agent_notification_outbox(eventKey);
CREATE INDEX IF NOT EXISTS index_agent_notification_outbox_sessionId_generation_deliveredAt_retiredAt_seq ON agent_notification_outbox(sessionId, generation, deliveredAt, retiredAt, seq);
