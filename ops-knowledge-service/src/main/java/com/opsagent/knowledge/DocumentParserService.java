package com.opsagent.knowledge;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 解析受支持的文档，并按固定窗口和重叠长度生成文本切片。 */
@Service
public class DocumentParserService {
    private final KnowledgeProperties p;

    DocumentParserService(KnowledgeProperties p) {
        this.p = p;
    }

    String parse(Path path, String ext) throws IOException {
        if (Set.of("txt", "md", "markdown").contains(ext))
            return Files.readString(path, StandardCharsets.UTF_8);
        try (var in = Files.newInputStream(path)) {
            var h = new BodyContentHandler(-1);
            new AutoDetectParser().parse(in, h, new Metadata());
            return h.toString();
        } catch (Exception e) {
            throw new IOException("文档内容无法解析", e);
        }
    }

    List<String> chunks(String raw) {
        String text = raw.replace("\r\n", "\n").trim();
        if (text.isBlank()) throw new IllegalArgumentException("文档没有可解析文本，扫描 PDF 暂不支持 OCR");
        if (p.getChunkSize() < 200
                || p.getChunkOverlap() < 0
                || p.getChunkOverlap() >= p.getChunkSize())
            throw new IllegalStateException("切片配置不合法");
        List<String> out = new ArrayList<>();
        // 相邻切片保留重叠上下文，降低答案落在切片边界时的信息损失。
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(start + p.getChunkSize(), text.length());
            out.add(text.substring(start, end));
            if (end == text.length()) break;
            start = end - p.getChunkOverlap();
        }
        return out;
    }
}
