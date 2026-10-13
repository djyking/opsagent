package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验证正文来自真实文件、权限前后重核、分页无重叠和不可读状态的真实边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeDocumentContentTest {
    @TempDir Path directory;
    KnowledgeRepository repository;
    KnowledgeService knowledge;
    VisitorKnowledgeService visitor;
    TicketAccessClient tickets;
    DocumentBodyReader reader;
    KnowledgeDocumentContentService content;
    Map<String, Object> document;
    SimpleMeterRegistry metrics;

    @BeforeEach
    void setup() throws Exception {
        var properties = new KnowledgeProperties();
        properties.setStorageRoot(directory.toString());
        var storage = new LocalFileStorageService(properties);
        repository = mock(KnowledgeRepository.class);
        visitor = mock(VisitorKnowledgeService.class);
        tickets = mock(TicketAccessClient.class);
        reader = spy(new DocumentBodyReader());
        metrics = new SimpleMeterRegistry();
        knowledge =
                new KnowledgeService(
                        repository,
                        storage,
                        mock(DocumentParserService.class),
                        mock(DocumentParsePublisher.class),
                        mock(KnowledgeIndexService.class),
                        mock(KnowledgeIndexCompensationService.class),
                        metrics,
                        properties,
                        tickets);
        knowledge.visitorKnowledge(visitor);
        content = new KnowledgeDocumentContentService(knowledge, storage, reader, properties);
        document =
                new LinkedHashMap<>(
                        Map.of(
                                "id",
                                1036L,
                                "version",
                                1,
                                "visibility",
                                "PUBLIC",
                                "review_status",
                                "PUBLISHED",
                                "create_by",
                                10L,
                                "file_type",
                                "md",
                                "storage_path",
                                "manual.md",
                                "original_name",
                                "manual.md"));
        when(repository.document(1036L)).thenAnswer(invocation -> new LinkedHashMap<>(document));
        Files.writeString(
                directory.resolve("manual.md"),
                "# Actual source\n\nThe original [chunk:42] is preserved.\n");
        actor(-1, "DEMO");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void publishedMarkdownIsExactOriginalAndNeverConcatenatesChunks() throws Exception {
        when(repository.chunks(1036L)).thenReturn(List.of(Map.of("content", "repeated overlap")));
        var result = content.page(1036, 1, 1);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.source()).isEqualTo("ORIGINAL_FILE_TEXT");
        assertThat(result.format()).isEqualTo("MARKDOWN");
        assertThat(result.text()).isEqualTo(Files.readString(directory.resolve("manual.md")));
        verify(repository, never()).chunks(anyLong());
        verify(repository, times(2)).document(1036);
    }

    @Test
    void visitorCannotReadPrivateOrUnpublishedSourceButOwnerCan() {
        document.put("review_status", "DRAFT");
        assertThatThrownBy(() -> content.page(1036, 1, 1)).isInstanceOf(BusinessException.class);
        document.put("review_status", "PUBLISHED");
        document.put("visibility", "PRIVATE");
        assertThatThrownBy(() -> content.page(1036, 1, 1)).isInstanceOf(BusinessException.class);
        actor(10, "USER");
        assertThat(content.page(1036, 1, 1).status()).isEqualTo("AVAILABLE");
    }

    @Test
    void publicLinkedTicketStillRequiresTicketAccess() {
        document.put("ticket_id", 2053L);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权查看工单"))
                .when(tickets)
                .requireVisible(2053L);
        assertThatThrownBy(() -> content.page(1036, 1, 1)).hasMessageContaining("无权查看工单");
        verifyNoInteractions(reader);
    }

    @Test
    void experiencePermissionIsRequiredEvenForAdminAndRecheckedAfterReading() throws Exception {
        document.put("review_status", "EXPERIENCE");
        document.put("create_by", -1L);
        actor(1, "ADMIN");
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权访问此体验文档"))
                .when(visitor)
                .requireVisible(1036);
        assertThatThrownBy(() -> content.page(1036, 1, 1)).hasMessageContaining("无权访问");
        reset(visitor);
        actor(-1, "DEMO");
        doNothing()
                .doThrow(new BusinessException(ErrorCode.FORBIDDEN, "VISITOR_REVOKED"))
                .when(visitor)
                .requireVisible(1036);
        assertThatThrownBy(() -> content.page(1036, 1, 1)).hasMessageContaining("VISITOR_REVOKED");
        verify(reader).read(any(), eq("md"));
    }

    @Test
    void missingOriginalDoesNotPretendExistingChunksAreTheBody() throws Exception {
        Files.delete(directory.resolve("manual.md"));
        when(repository.chunks(1036L))
                .thenReturn(List.of(Map.of("content", "available old chunk")));
        var result = content.page(1036, 1, 1);
        assertThat(result.status()).isEqualTo("SOURCE_MISSING");
        assertThat(result.text()).isEmpty();
        assertThat(result.notice()).contains("原文件缺失");
        verify(repository, never()).chunks(anyLong());
    }

    @Test
    void sourceCannotEscapeStorageAndCannotCrossARevision() throws Exception {
        document.put("storage_path", "../private-outside.md");
        assertThat(content.page(1036, 1, 1).status()).isEqualTo("UNREADABLE");
        document.put("storage_path", "manual.md");
        assertThatThrownBy(() -> content.page(1036, 1, 2)).hasMessageContaining("版本已变化");
        doAnswer(
                        invocation -> {
                            String body = (String) invocation.callRealMethod();
                            document.put("version", 2);
                            return body;
                        })
                .when(reader)
                .read(any(), anyString());
        assertThatThrownBy(() -> content.page(1036, 1, 1)).hasMessageContaining("读取期间已更新");
    }

    @Test
    void longTextPagesPreserveEveryCharacterIncludingSurrogateBoundary() throws Exception {
        String original = "a".repeat(19_999) + "😀" + "完整结尾";
        Files.writeString(directory.resolve("manual.md"), original);
        var first = content.page(1036, 1, 1);
        var second = content.page(1036, 2, 1);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.text() + second.text()).isEqualTo(original);
        assertThatThrownBy(() -> content.page(1036, 3, 1)).hasMessageContaining("页码超过");
        Files.writeString(
                directory.resolve("manual.md"), "字".repeat(DocumentBodyReader.MAX_CHARACTERS + 1));
        var tooLarge = content.page(1036, 1, 1);
        assertThat(tooLarge.status()).isEqualTo("TOO_LARGE");
        assertThat(tooLarge.text()).isEmpty();
    }

    @Test
    void extractsActualDocxParagraphsAndTableOnceWithoutChunkOverlap() throws Exception {
        document.put("file_type", "docx");
        document.put("storage_path", "manual.docx");
        try (var doc = new XWPFDocument();
                var stream = Files.newOutputStream(directory.resolve("manual.docx"))) {
            doc.createParagraph().createRun().setText("第一段真实正文");
            var row = doc.createTable(1, 2).getRow(0);
            row.getCell(0).setText("字段");
            row.getCell(1).setText("真实值");
            doc.createParagraph().createRun().setText("最后一段");
            doc.write(stream);
        }
        var result = content.page(1036, 1, 1);
        assertThat(result.source()).isEqualTo("EXTRACTED_FILE_TEXT");
        assertThat(result.text()).isEqualTo("第一段真实正文\n\n字段\t真实值\n\n最后一段\n\n");
    }

    @Test
    void extractsRealPdfAndReportsImageOnlyOrMalformedTextHonestly() throws Exception {
        document.put("file_type", "pdf");
        document.put("storage_path", "manual.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Actual PDF body");
                stream.endText();
            }
            doc.save(directory.resolve("manual.pdf").toFile());
        }
        assertThat(content.page(1036, 1, 1).text()).contains("Actual PDF body");
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(directory.resolve("manual.pdf").toFile());
        }
        assertThat(content.page(1036, 1, 1).status()).isEqualTo("NO_TEXT");
        document.put("file_type", "txt");
        document.put("storage_path", "bad.txt");
        Files.write(directory.resolve("bad.txt"), new byte[] {(byte) 0xc3, 0x28});
        assertThat(content.page(1036, 1, 1).status()).isEqualTo("UNREADABLE");
    }

    @Test
    void publicContentResponseNeverAllowsBrowserCaching() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new KnowledgeDocumentContentController(content))
                        .build();
        mvc.perform(get("/api/knowledge/documents/1036/content").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(
                        jsonPath("$.data.text")
                                .value(Files.readString(directory.resolve("manual.md"))));
    }

    private void actor(long id, String role) {
        var principal = new OpsPrincipal(id, "reader", "content-test", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
