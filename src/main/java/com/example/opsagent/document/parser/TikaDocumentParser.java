package com.example.opsagent.document.parser;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 使用 Apache Tika 提取文本型 PDF 和 DOCX 的可检索文本。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of("pdf", "docx");

    @Override
    public boolean supports(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public String parse(Path path) throws IOException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (InputStream inputStream = Files.newInputStream(path)) {
            parser.parse(inputStream, handler, new Metadata());
            return handler.toString();
        } catch (TikaException | SAXException exception) {
            throw new IOException("文档内容无法解析", exception);
        }
    }
}
