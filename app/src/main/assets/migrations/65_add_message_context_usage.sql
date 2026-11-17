ALTER TABLE agent_messages ADD COLUMN contextInputTokens INTEGER;
ALTER TABLE agent_messages ADD COLUMN contextInputBudget INTEGER;
ALTER TABLE agent_messages ADD COLUMN contextEstimated INTEGER NOT NULL DEFAULT 1;
