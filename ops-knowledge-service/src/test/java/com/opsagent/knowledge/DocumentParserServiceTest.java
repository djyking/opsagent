package com.opsagent.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证结构化解析、Token 切分、章节、代码表格和 PDF 页码元数据。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DocumentParserServiceTest {
    private final TokenCounter tokenCounter = new ApproxTokenCounter();
    private DocumentParserService parser;
    private KnowledgeProperties properties;

    @TempDir
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        properties = new KnowledgeProperties();
        properties.getChunk().setTargetTokens(80);
        properties.getChunk().setMaxTokens(120);
        properties.getChunk().setMinTokens(10);
        properties.getChunk().setOverlapTokens(12);
        parser = new DocumentParserService(properties, tokenCounter);
    }

    @Test
    void shouldPreserveMarkdownStructureAndCommands() throws Exception {
        Path source = Path.of("src/test/resources/rag-test-docs/redis_sop.md");

        List<StructuredChunk> chunks = parser.parse(source, "md", "redis_sop.md", 3);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(120);
            assertThat(chunk.strategyVersion()).isEqualTo("structure-v1");
            assertThat(chunk.documentVersion()).isEqualTo(3);
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.headingPath())
                .contains("Redis 生产故障手册", "缓存命中率下降", "排查步骤"));
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.containsCode()).isTrue();
            assertThat(chunk.content()).contains("redis-cli", "127.0.0.1", "6379");
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.containsTable()).isTrue());
        assertThat(chunks.get(0).embeddingText("redis_sop.md"))
                .contains("文档：redis_sop.md", "章节：", "正文：");
    }

    @Test
    void shouldSplitLargeChineseParagraphWithoutExceedingMaximum() {
        properties.getChunk().setTargetTokens(20);
        properties.getChunk().setMaxTokens(30);
        properties.getChunk().setMinTokens(5);
        properties.getChunk().setOverlapTokens(5);
        String paragraph = "第一步检查连接池。第二步检查慢查询。第三步检查锁等待。第四步执行恢复。".repeat(5);
        DocumentBlock block = new DocumentBlock(
                BlockType.PARAGRAPH,
                paragraph,
                List.of("MySQL手册", "连接池耗尽"),
                null,
                null,
                0,
                null);

        List<StructuredChunk> chunks = parser.chunks(List.of(block), "MD", 1);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount())
                .isLessThanOrEqualTo(30));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.headingPath())
                .containsExactly("MySQL手册", "连接池耗尽"));
    }

    @Test
    void shouldPreserveDocxHeadingAndTable() throws Exception {
        Path file = temporaryDirectory.resolve("nacos_sop.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream output = Files.newOutputStream(file)) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("配置发布失败");
            document.createParagraph().createRun().setText("检查 dataId、group 和 namespace 是否一致。");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段");
            table.getRow(0).getCell(1).setText("值");
            table.getRow(1).getCell(0).setText("dataId");
            table.getRow(1).getCell(1).setText("ops-rag-service.yaml");
            document.write(output);
        }

        List<StructuredChunk> chunks = parser.parse(file, "docx", "Nacos SOP", 1);

        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk.headingPath())
                .contains("配置发布失败"));
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.containsTable()).isTrue();
            assertThat(chunk.content()).contains("dataId", "ops-rag-service.yaml");
        });
    }

    @Test
    void shouldPreservePdfPageNumber() throws Exception {
        Path file = temporaryDirectory.resolve("mysql_runbook.pdf");
        try (PDDocument document = new PDDocument()) {
            addPdfPage(document, "Page one: check HikariPool and HTTP 429.");
            addPdfPage(document, "Page two: verify database recovery.");
            document.save(file.toFile());
        }

        List<StructuredChunk> chunks = parser.parse(file, "pdf", "MySQL Runbook", 1);

        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(chunks.size() - 1).pageEnd()).isEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.pageStart()).isNotNull();
            assertThat(chunk.pageEnd()).isNotNull();
        });
    }

    @Test
    void shouldParseTxtByParagraph() throws Exception {
        Path file = temporaryDirectory.resolve("rabbitmq_sop.txt");
        Files.writeString(file, "检查队列堆积和 unacked。\n\n检查死信队列和 consumer。\n");

        List<StructuredChunk> chunks = parser.parse(file, "txt", "RabbitMQ SOP", 1);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).headingPath()).containsExactly("RabbitMQ SOP");
        assertThat(chunks.get(0).content()).contains("unacked", "死信队列");
    }

    private void addPdfPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
