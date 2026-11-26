package com.wuxi.ai.web;

import com.wuxi.ai.llm.LlmClient;
import com.wuxi.ai.llm.LlmClientRegistry;
import com.wuxi.ai.llm.LlmConfig;
import com.wuxi.ai.llm.LlmException;
import com.wuxi.ai.llm.LlmRequest;
import com.wuxi.ai.llm.LlmResponse;
import com.wuxi.ai.llm.Msg;
import com.wuxi.ai.llm.PromptTemplate;
import com.wuxi.ai.rag.Embedder;
import com.wuxi.ai.rag.OpenAiCompatibleEmbedder;
import com.wuxi.ai.rag.PgVectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 业务逻辑：检索 + 生成。
 *
 * 这是整个应用的核心，和 HTTP 层完全解耦——
 * 将来换成 Spring Boot 或 CLI，这个类一行都不用改。
 *
 * 流程：
 *   问题 → 向量化 → pgvector 检索 topK → 拼上下文 → 调大模型 → 返回答案 + 引用
 */
public final class RagService implements AutoCloseable {

    /** 每段参考资料最多塞多少字，控制 token 消耗 */
    private static final int MAX_SNIPPET_CHARS = 600;

    private final Embedder embedder;
    private final PgVectorStore store;
    private final LlmClient llm;
    private final PromptTemplate template;

    public RagService() throws Exception {
        this.embedder = OpenAiCompatibleEmbedder.local();
        this.store = PgVectorStore.local();

        LlmConfig config = LlmConfig.load();
        LlmClientRegistry registry = LlmClientRegistry.from(config);
        this.llm = registry.resolve(null);

        this.template = PromptTemplate.create()
                .role("""
                        你是工业机器人设备维护助手，服务对象是一线维修工和操作工。
                        你的回答会直接用于现场作业，必须准确、可执行。""")
                .task("""
                        仅依据【参考资料】回答用户的问题。
                        如果参考资料里找不到答案，就明确说"资料中未提及"。""")
                .constraint(
                        "只能使用参考资料中的信息，禁止凭常识或经验补充",
                        "资料中没有答案时，直接回答「资料中未提及」，绝对不要猜测",
                        "每句关键结论后面用 [1] [2] 标注引用的资料编号",
                        "用简洁的中文，先给结论，再给必要说明和步骤",
                        "涉及数值、型号、规格时必须原样引用，不要改写",
                        "如果资料提到相关图示，在末尾提示「见 P页码 图示」")
                .outputFormat("""
                        先给一句话结论。
                        需要时用编号列出步骤或要点。
                        末尾另起一行写「依据：[1] [2]」""");
    }

    public LlmClient llm() {
        return llm;
    }

    /**
     * 回答一个问题。
     *
     * synchronized：PgVectorStore 内部是单连接，多线程并发会出错。
     * 本地单用户场景够了，真要多用户得换成连接池。
     */
    public synchronized AskResponse ask(String question, int topK) throws Exception {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        // ① 向量化
        long t0 = System.currentTimeMillis();
        float[] qv = embedder.embed(question);
        long embedMs = System.currentTimeMillis() - t0;

        // ② 检索
        long t1 = System.currentTimeMillis();
        List<PgVectorStore.Hit> hits = store.search(qv, Math.max(1, Math.min(topK, 10)));
        long searchMs = System.currentTimeMillis() - t1;

        if (hits.isEmpty()) {
            return new AskResponse(
                    "知识库中还没有内容。请先运行 RagIndexDemo 建立索引。",
                    List.of(), false, embedMs, searchMs, 0, 0);
        }

        // ③ 拼上下文
        List<Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            PgVectorStore.Hit h = hits.get(i);
            String pages = h.startPage() == h.endPage()
                    ? "P" + h.startPage()
                    : "P" + h.startPage() + "-" + h.endPage();
            String section = (h.section() == null || h.section().isBlank()) ? "（无章节）" : h.section();

            context.append("【资料").append(i + 1).append("】《")
                    .append(h.docId()).append("》 ").append(pages).append(" ").append(section)
                    .append('\n').append(truncate(h.content(), MAX_SNIPPET_CHARS))
                    .append("\n\n");

            sources.add(new Source(h.docId(), section, pages, round(h.score()),
                    truncate(h.content(), 160), h.images()));
        }

        // ④ 调模型
        List<Msg> messages = new ArrayList<>(template.toMessages());
        messages.add(Msg.user("【参考资料】\n" + context + "\n【问题】" + question));

        long t2 = System.currentTimeMillis();
        LlmResponse resp;
        try {
            resp = llm.chat(new LlmRequest(null, null, messages, 0.1, 1200));
        } catch (LlmException e) {
            return new AskResponse("模型调用失败：" + e.getMessage(),
                    sources, false, embedMs, searchMs, 0, 0);
        }
        long llmMs = System.currentTimeMillis() - t2;

        String answer = resp.text() == null ? "" : resp.text().strip();
        boolean answered = !answer.isBlank() && !answer.contains("资料中未提及");

        if (answer.isBlank()) {
            answer = "模型没有返回内容，请换个问法重试。";
        }

        return new AskResponse(answer, sources, answered, embedMs, searchMs, llmMs, resp.totalTokens());
    }

    private String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    @Override
    public void close() throws Exception {
        store.close();
    }
}
