-- Chat memory store for LangChain4j (messages serialized as LC4j JSON).
CREATE TABLE IF NOT EXISTS lc4j_chat_memory (
    memory_id  VARCHAR(256) PRIMARY KEY,
    messages   TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
