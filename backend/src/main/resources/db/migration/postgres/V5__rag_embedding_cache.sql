CREATE TABLE rag_embedding_cache (
    model_id VARCHAR(160) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    dimensions INTEGER NOT NULL,
    vector_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (model_id, content_hash)
);

CREATE INDEX idx_rag_embedding_updated_at
    ON rag_embedding_cache(updated_at DESC);
