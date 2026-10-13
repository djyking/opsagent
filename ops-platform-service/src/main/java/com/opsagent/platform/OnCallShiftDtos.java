package com.opsagent.platform;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 值班管理分页和独立日历查询契约。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class OnCallShiftDtos {
    private OnCallShiftDtos() {}

    /**
     * 当前及未来班次的分页条件。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record PageQuery(
            @Min(1) Integer pageNum,
            @Min(1) @Max(50) Integer pageSize,
            @Positive Long scheduleId) {
        public PageQuery {
            pageNum = pageNum == null ? 1 : pageNum;
            pageSize = pageSize == null ? 10 : pageSize;
        }
    }

    /**
     * 日历半开区间，返回与区间相交的已启用计划班次。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record CalendarQuery(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @Positive Long scheduleId) {}

    /**
     * 班次查询结果，时间沿用原有本地日期时间约定。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Shift(
            long id,
            long scheduleId,
            String scheduleName,
            String roleType,
            long userId,
            String userName,
            LocalDateTime startTime,
            LocalDateTime endTime) {}
}
