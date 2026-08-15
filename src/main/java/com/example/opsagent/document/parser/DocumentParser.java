package com.example.opsagent.document.parser;

import java.nio.file.Path;

/**
 * 根据文件类型解析本地文本文档的策略接口。
 *
 * @author heyu
 * @since 2026/8/15
 */
public interface DocumentParser {

    boolean supports(String fileType);

    String parse(Path file);
}
