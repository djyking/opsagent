package com.opsagent.rag;

import com.opsagent.common.core.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** 编排知识检索与回答生成；外部模型关闭时返回可解释的本地降级结果。 */
@Service
public class RagService {
    private final KnowledgeClient knowledge;
    private final boolean llmEnabled;

    RagService(KnowledgeClient k, @Value("${ops.rag.llm-enabled:false}") boolean enabled) {
        knowledge = k;
        llmEnabled = enabled;
    }

    Answer ask(String question, int topK) {
        List<Map<String, Object>> refs;
        try {
            var result = knowledge.search(question, topK);
            refs = result.data() == null ? List.of() : result.data();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "知识检索暂时不可用");
        }
        String answer;
        if (refs.isEmpty()) answer = "未检索到足够的知识依据。请补充运维文档，或提供更具体的错误信息、时间范围和影响面。";
        else if (llmEnabled)
            answer =
                    "已完成知识检索；LLM 调用适配器需要通过 OPS_LLM_BASE_URL、OPS_LLM_API_KEY 和 OPS_LLM_MODEL"
                        + " 配置后生成综合答案。";
        else answer = "根据已检索的运维知识，建议先核对引用片段，按影响面、资源使用率、连接/线程池和上下游依赖逐项排查。当前未启用外部 LLM，因此返回基础检索结论。";
        return new Answer(answer, refs, llmEnabled ? "configured-llm" : "retrieval-fallback");
    }

    record Answer(String answer, List<Map<String, Object>> references, String model) {}
}
