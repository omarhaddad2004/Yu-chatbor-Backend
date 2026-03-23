CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    full_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(180) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id BIGINT,
    title VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL,
    sender VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);

CREATE TABLE documents (
    id              BIGSERIAL PRIMARY KEY,
    source_url      VARCHAR(1000) NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    raw_content     TEXT,
    cleaned_content TEXT,
    content_type    VARCHAR(50)   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE chunks (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT      NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT         NOT NULL,
    text         TEXT        NOT NULL,
    summary      TEXT,
    heading      VARCHAR(500),
    importance   REAL,
    keywords     TEXT
);

CREATE TABLE chunk_vectors (
    id        BIGSERIAL PRIMARY KEY,
    chunk_id  BIGINT      NOT NULL UNIQUE REFERENCES chunks(id) ON DELETE CASCADE,
    embedding VECTOR(1024) NOT NULL
);

CREATE TABLE query_history (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT,
    question     TEXT        NOT NULL,
    answer       TEXT        NOT NULL,
    sources_json TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_query_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);