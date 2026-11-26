package com.wuxi.ai.rag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * pgvector 存储层。
 *
 * 为什么用 pgvector 而不是专用向量数据库（Milvus / Qdrant）：
 *   - 向量和业务数据（文档、权限、日志）可以放在同一个库，不用做数据同步
 *   - 就是标准 PostgreSQL，SQL 通用，运维成本低
 *   - 十万级向量毫无压力，工厂场景完全够用
 *
 * 关键 SQL 知识点：
 *   embedding 列的类型是 vector(1024)，插入时要显式转换：?::vector
 *   相似度用余弦距离运算符 <=>，score = 1 - distance
 */
public final class PgVectorStore implements AutoCloseable {

    /** 检索命中结果 */
    public record Hit(
            String id,
            String docId,
            int seq,
            String section,
            int startPage,
            int endPage,
            String content,
            List<String> images,
            double score
    ) {
    }

    private final Connection conn;

    public PgVectorStore(String jdbcUrl, String user, String password) throws SQLException {
        this.conn = DriverManager.getConnection(jdbcUrl, user, password);
        this.conn.setAutoCommit(false);
    }

    /** 本机 Docker 里的 pgvector（端口 5433，避开默认 5432） */
    public static PgVectorStore local() throws SQLException {
        return new PgVectorStore("jdbc:postgresql://127.0.0.1:5433/ragdb", "postgres", "postgres");
    }

    /**
     * 建表 + 建索引。可重复执行。
     *
     * @param dim 向量维度，必须和 embedding 模型一致（bge-m3 = 1024）
     */
    public void ensureSchema(int dim) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS documents (
                      id          TEXT PRIMARY KEY,
                      title       TEXT NOT NULL,
                      path        TEXT,
                      page_count  INT,
                      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS chunks (
                      id          TEXT PRIMARY KEY,
                      doc_id      TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                      seq         INT NOT NULL,
                      section     TEXT,
                      start_page  INT,
                      end_page    INT,
                      content     TEXT NOT NULL,
                      embedding   vector(%d),
                      images      JSONB,
                      model       TEXT,
                      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.formatted(dim));

            st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(doc_id)");

            // HNSW：近似最近邻索引，查询复杂度从全表扫描降到对数级
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chunks_embedding
                      ON chunks USING hnsw (embedding vector_cosine_ops)
                    """);
        }
        conn.commit();
    }

    /** 写入/更新文档元信息 */
    public void upsertDocument(SourceDocument doc) throws SQLException {
        String sql = """
                INSERT INTO documents (id, title, path, page_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  title = EXCLUDED.title,
                  path = EXCLUDED.path,
                  page_count = EXCLUDED.page_count
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, doc.id());
            ps.setString(2, doc.title());
            ps.setString(3, doc.path());
            ps.setInt(4, doc.pageCount());
            ps.executeUpdate();
        }
        conn.commit();
    }

    /**
     * 批量写入 chunk（自动算向量）。
     *
     * @return 成功写入条数
     */
    public int insertChunks(List<Chunk> chunks, Embedder embedder, int batchSize) throws SQLException {
        if (chunks.isEmpty()) {
            return 0;
        }
        // 先清掉这些文档的旧 chunk，避免重复入库
        List<String> docIds = chunks.stream().map(Chunk::docId).distinct().toList();
        for (String docId : docIds) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chunks WHERE doc_id = ?")) {
                ps.setString(1, docId);
                ps.executeUpdate();
            }
        }

        String sql = """
                INSERT INTO chunks
                  (id, doc_id, seq, section, start_page, end_page, content, embedding, images, model)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?::jsonb, ?)
                """;

        int written = 0;
        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<Chunk> slice = chunks.subList(from, Math.min(from + batchSize, chunks.size()));
            List<String> texts = slice.stream().map(Chunk::text).toList();
            List<float[]> vectors = embedder.embedBatch(texts);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < slice.size(); i++) {
                    Chunk c = slice.get(i);
                    ps.setString(1, c.id());
                    ps.setString(2, c.docId());
                    ps.setInt(3, c.seq());
                    ps.setString(4, c.section());
                    ps.setInt(5, c.startPage());
                    ps.setInt(6, c.endPage());
                    ps.setString(7, c.text());
                    ps.setString(8, toVectorLiteral(vectors.get(i)));
                    ps.setString(9, toJsonArray(c.images()));
                    ps.setString(10, embedder.modelName());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            written += slice.size();
            System.out.printf("   已写入 %d / %d%n", written, chunks.size());
        }
        return written;
    }

    /** 语义检索 */
    public List<Hit> search(float[] queryVector, int topK) throws SQLException {
        String sql = """
                SELECT id, doc_id, seq, section, start_page, end_page, content, images,
                       1 - (embedding <=> ?::vector) AS score
                FROM chunks
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        String vec = toVectorLiteral(queryVector);
        List<Hit> hits = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vec);
            ps.setString(2, vec);
            ps.setInt(3, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new Hit(
                            rs.getString("id"),
                            rs.getString("doc_id"),
                            rs.getInt("seq"),
                            rs.getString("section"),
                            rs.getInt("start_page"),
                            rs.getInt("end_page"),
                            rs.getString("content"),
                            parseJsonArray(rs.getString("images")),
                            rs.getDouble("score")));
                }
            }
        }
        return hits;
    }

    public int count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM chunks")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 彻底清空（调试用） */
    public void truncate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE chunks, documents CASCADE");
        }
        conn.commit();
    }

    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String s = json.trim();
        s = s.substring(1, s.length() - 1);
        // 注意：PostgreSQL 的 jsonb 会在逗号后补空格（["a", "b"]），
        // 所以分隔正则必须允许空白，否则整串会被当成一个元素。
        for (String part : s.split(",\\s*(?=\")")) {
            String v = part.trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                out.add(v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
