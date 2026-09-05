package com.opsagent.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 看板的分页请求与统计响应。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class SlaDtos {
    private SlaDtos() {}

    /**
     * 在数据库中应用的分页与筛选条件。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Query(
            @Min(1) Integer pageNum,
            @Min(1) @Max(100) Integer pageSize,
            @Pattern(regexp = "all|risk|breached") String view,
            @Pattern(regexp = "|LOW|MEDIUM|HIGH|URGENT") String priority,
            @Size(max = 64) String service,
            @Size(max = 200) String keyword) {
        public Query {
            pageNum = pageNum == null ? 1 : pageNum;
            pageSize = pageSize == null ? 10 : pageSize;
            view = view == null || view.isBlank() ? "all" : view;
            priority = priority == null ? "" : priority;
            service = service == null ? "" : service.trim();
            keyword = keyword == null ? "" : keyword.trim();
        }

        public String keywordPattern() {
            return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        }
    }

    /**
     * 分页列表中的 SLA 工单明细。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Row(
            long id,
            long ticketId,
            String ticketNo,
            String title,
            String priority,
            String status,
            String affectedCiCode,
            LocalDateTime responseDeadline,
            LocalDateTime resolutionDeadline,
            String responseStatus,
            String resolutionStatus,
            int escalationLevel) {}

    /**
     * 全量未删除工单的 SLA 聚合，不受列表页码和筛选影响。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Counts(long total, long running, long risk, long dashboardRisk, long breached, long completed) {}

    /**
     * 聚合指标、完整服务选项与服务端统计时间。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Summary(Counts counts, List<String> services, LocalDateTime checkedAt) {}
}
