package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 在重排服务关闭时保持 RRF 原顺序，保证 RAG 主链路仍可运行。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class NoOpRerankProvider implements RerankProvider {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<RerankResult> rerank(
            String query, List<RerankDocument> candidates, int topN) {
        int maximum = Math.min(Math.max(0, topN), candidates.size());
        List<RerankResult> result = new ArrayList<>();
        for (int index = 0; index < maximum; index++) {
            result.add(new RerankResult(index, 0.0D, index + 1));
        }
        return List.copyOf(result);
    }
}
