package com.example.opsagent.document.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证 TXT 和 Markdown 解析策略的文件类型判断及 UTF-8 读取。
 *
 * @author heyu
 * @since 2026/8/15
 */
class DocumentParserTest {

    @TempDir private Path tempDirectory;

    @Test
    void shouldParseTxtAndMarkdownFiles() throws Exception {
        Path txt = tempDirectory.resolve("guide.txt");
        Path markdown = tempDirectory.resolve("guide.md");
        Files.writeString(txt, "检查服务日志", StandardCharsets.UTF_8);
        Files.writeString(markdown, "# 处理步骤", StandardCharsets.UTF_8);

        TxtDocumentParser txtParser = new TxtDocumentParser();
        MarkdownDocumentParser markdownParser = new MarkdownDocumentParser();

        assertThat(txtParser.supports("TXT")).isTrue();
        assertThat(txtParser.parse(txt)).isEqualTo("检查服务日志");
        assertThat(markdownParser.supports("markdown")).isTrue();
        assertThat(markdownParser.parse(markdown)).isEqualTo("# 处理步骤");
    }

    @Test
    void shouldRecognizeOnlySupportedTikaDocumentExtensions() {
        TikaDocumentParser parser = new TikaDocumentParser();

        assertThat(parser.supports("PDF")).isTrue();
        assertThat(parser.supports("docx")).isTrue();
        assertThat(parser.supports("doc")).isFalse();
        assertThat(parser.supports("exe")).isFalse();
    }

    @Test
    void shouldExtractTextFromDocxAndTextPdf() throws Exception {
        Path docx = tempDirectory.resolve("guide.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("restart application service");
            try (var output = Files.newOutputStream(docx)) {
                document.write(output);
            }
        }
        Path pdf = tempDirectory.resolve("guide.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("clean disk logs");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        TikaDocumentParser parser = new TikaDocumentParser();
        assertThat(parser.parse(docx)).contains("restart application service");
        assertThat(parser.parse(pdf)).contains("clean disk logs");
    }
}
