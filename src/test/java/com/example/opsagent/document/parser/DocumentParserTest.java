package com.example.opsagent.document.parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 TXT 和 Markdown 解析策略的文件类型判断及 UTF-8 读取。
 *
 * @author heyu
 * @since 2026/8/15
 */
class DocumentParserTest {

    @TempDir
    private Path tempDirectory;

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
}
