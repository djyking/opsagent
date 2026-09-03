package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 移除模型生成但检索结果中不存在的片段引用，防止伪造来源被当成可信证据。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Component
public class CitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[chunk:(\\d+)]");

    String validate(String answer, List<RetrievedChunk> chunks) {
        Set<Long> allowed = chunks.stream().map(RetrievedChunk::chunkId).collect(Collectors.toSet());
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            long id = Long.parseLong(matcher.group(1));
            matcher.appendReplacement(safe, allowed.contains(id) ? matcher.group() : "[无效引用已移除]");
        }
        matcher.appendTail(safe);
        return safe.toString();
    }
}
