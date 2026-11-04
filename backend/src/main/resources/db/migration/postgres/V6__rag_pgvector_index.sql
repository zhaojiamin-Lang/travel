-- 生产检索索引与 Embedding 文本缓存分开：前者服务于相似度召回，后者避免重复调用模型。
-- pgvector 扩展由数据库管理员预安装；应用账号只需要建表和索引权限。
CREATE TABLE rag_chunk_embedding (
    model_id VARCHAR(160) NOT NULL,
    chunk_id VARCHAR(160) NOT NULL,
    dimensions INTEGER NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (model_id, chunk_id)
);

-- 余弦距离与 DashScope Embedding 的语义相似度计算一致。
CREATE INDEX idx_rag_chunk_embedding_hnsw
    ON rag_chunk_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

