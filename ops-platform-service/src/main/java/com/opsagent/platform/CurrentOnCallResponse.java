package com.opsagent.platform;

import java.util.List;
import java.util.Map;

/**
 * 当前值班查询契约；人员信息位于 members，空班次由 fallback 明确表示。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record CurrentOnCallResponse(
        boolean fallback, String message, List<Map<String, Object>> members) {}
