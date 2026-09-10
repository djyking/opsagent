package com.opsagent.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 从实际原文件读取正文，不使用带重叠的检索切片，不触发解析任务或向量化。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class DocumentBodyReader {
    static final int MAX_CHARACTERS = 1_000_000;

    String read(Path source, String type) throws IOException {
        var output = new BoundedText();
        switch (type) {
            case "txt", "md", "markdown" -> {
                try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[8192];
                    int count;
                    while ((count = reader.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
            case "pdf" -> {
                try (var document = Loader.loadPDF(source.toFile())) {
                    if (document.getNumberOfPages() > 500) throw new ContentLimitException();
                    var stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    stripper.writeText(document, output);
                }
            }
            case "docx" -> {
                try (var input = Files.newInputStream(source);
                        var document = new XWPFDocument(input)) {
                    for (var element : document.getBodyElements()) {
                        if (element instanceof XWPFParagraph paragraph) {
                            output.write(paragraph.getText());
                            output.write("\n\n");
                        } else if (element instanceof XWPFTable table) {
                            for (var row : table.getRows()) {
                                boolean first = true;
                                for (var cell : row.getTableCells()) {
                                    if (!first) output.write("\t");
                                    output.write(cell.getText());
                                    first = false;
                                }
                                output.write("\n");
                            }
                            output.write("\n");
                        }
                    }
                }
            }
            default -> throw new IOException("Unsupported source type");
        }
        return output.text.toString();
    }

    static final class ContentLimitException extends IOException {}

    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if ((long) text.length() + length > MAX_CHARACTERS
                    || System.nanoTime() > deadline
                    || Thread.currentThread().isInterrupted()) throw new ContentLimitException();
            text.append(buffer, offset, length);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
