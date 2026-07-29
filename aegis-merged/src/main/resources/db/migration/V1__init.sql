CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(256) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE TABLE IF NOT EXISTS assistant_audit_event (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant VARCHAR(256),
    user_id VARCHAR(256),
    conversation_id VARCHAR(256),
    source VARCHAR(64),
    elapsed_ms BIGINT,
    answer_chars INT,
    question TEXT,
    prev_hash VARCHAR(64) NOT NULL DEFAULT 'GENESIS',
    row_hash VARCHAR(64) NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS assistant_widget_event (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(256) NOT NULL,
    turn_seq INT NOT NULL,
    widget_type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_widget_event_conversation
    ON assistant_widget_event (conversation_id);

CREATE TABLE IF NOT EXISTS assistant_turn_counter (
    conversation_id VARCHAR(256) PRIMARY KEY,
    next_turn_seq INT NOT NULL DEFAULT 0
);
