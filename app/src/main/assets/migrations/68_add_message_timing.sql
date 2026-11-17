ALTER TABLE agent_messages ADD COLUMN toolStartedAtMs INTEGER;
ALTER TABLE agent_messages ADD COLUMN toolDurationMs INTEGER;
ALTER TABLE agent_messages ADD COLUMN turnDurationMs INTEGER;
