package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用独立 H2 数据验证班次真实分页、跨区间日历及管理权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OnCallShiftPaginationTest {
    private JdbcTemplate jdbc;
    private ItsmPlatformService service;
    private SimpleMeterRegistry metrics;
    private final LocalDateTime future = LocalDateTime.of(2100, 1, 1, 0, 0);

    @BeforeEach
    void setUp() {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:oncall-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE oncall_schedule(id BIGINT PRIMARY KEY,schedule_code VARCHAR(64),
                    schedule_name VARCHAR(128),service_ci_code VARCHAR(64),timezone VARCHAR(64),
                    enabled INT,update_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE oncall_shift(id BIGINT PRIMARY KEY,schedule_id BIGINT,role_type VARCHAR(16),
                    user_id BIGINT,user_name VARCHAR(64),start_time TIMESTAMP,end_time TIMESTAMP,
                    UNIQUE(schedule_id,role_type,start_time))
                """);
        for (int id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO oncall_schedule VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                    id, "schedule-" + id, "值班计划 " + id, "ops-ticket-service", "Asia/Shanghai", id == 3 ? 0 : 1);
        }
        metrics = new SimpleMeterRegistry();
        service = new ItsmPlatformService(new ItsmPlatformRepository(jdbc, metrics),
                mock(PlatformAuditRepository.class), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        metrics.close();
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void pagesBeyondOldLimitWithoutDuplicatesAndAcceptsAllPageSizes() {
        for (int id = 1; id <= 125; id++) {
            insert(id, 1, future.plusMinutes(id), future.plusDays(1));
        }
        Set<Long> ids = new HashSet<>();
        for (int number = 1; number <= 13; number++) {
            var page = service.shiftPage(new OnCallShiftDtos.PageQuery(number, 10, null));
            assertThat(page.total()).isEqualTo(125);
            assertThat(page.pageNum()).isEqualTo(number);
            assertThat(page.pageSize()).isEqualTo(10);
            assertThat(page.records()).hasSize(number == 13 ? 5 : 10);
            for (var row : page.records()) {
                assertThat(ids.add(row.id())).as("班次只出现在一个分页中").isTrue();
            }
        }
        assertThat(ids).hasSize(125);
        var last = service.shiftPage(new OnCallShiftDtos.PageQuery(999, 10, null));
        assertThat(last.pageNum()).isEqualTo(13);
        assertThat(last.records()).extracting(OnCallShiftDtos.Shift::id)
                .containsExactly(121L, 122L, 123L, 124L, 125L);
        assertThat(service.shiftPage(new OnCallShiftDtos.PageQuery(1, 20, null)).records()).hasSize(20);
        assertThat(service.shiftPage(new OnCallShiftDtos.PageQuery(1, 50, null)).records()).hasSize(50);
    }

    @Test
    void countsOnlyCurrentAndFutureShiftsWithTheSelectedPlanIncludingDisabledPlans() {
        insert(1, 1, future, future.plusDays(1));
        insert(2, 2, future, future.plusDays(1));
        insert(3, 3, future, future.plusDays(1));
        insert(4, 1, LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 2, 0, 0));
        assertThat(service.shiftPage(new OnCallShiftDtos.PageQuery(1, 10, null)).total()).isEqualTo(3);
        var filtered = service.shiftPage(new OnCallShiftDtos.PageQuery(1, 10, 2L));
        assertThat(filtered.total()).isOne();
        assertThat(filtered.records()).extracting(OnCallShiftDtos.Shift::id).containsExactly(2L);
        assertThat(service.shiftPage(new OnCallShiftDtos.PageQuery(1, 10, 3L)).records())
                .extracting(OnCallShiftDtos.Shift::id).containsExactly(3L);
        var empty = service.shiftPage(new OnCallShiftDtos.PageQuery(9, 20, 999L));
        assertThat(empty.total()).isZero();
        assertThat(empty.pageNum()).isOne();
        assertThat(empty.pageSize()).isEqualTo(20);
        assertThat(empty.records()).isEmpty();
    }

    @Test
    void deletingOnlyRowOnLastPageClampsToThePreviousValidPage() {
        for (int id = 1; id <= 11; id++) {
            insert(id, 1, future.plusMinutes(id), future.plusDays(1));
        }
        var query = new OnCallShiftDtos.PageQuery(2, 10, 1L);
        assertThat(service.shiftPage(query).records()).extracting(OnCallShiftDtos.Shift::id).containsExactly(11L);
        authenticate("ADMIN");
        service.deleteShift(11);
        var page = service.shiftPage(query);
        assertThat(page.total()).isEqualTo(10);
        assertThat(page.pageNum()).isOne();
        assertThat(page.records()).hasSize(10);
    }

    @Test
    void calendarKeepsAllOverlappingShiftsAndExcludesDisabledPlansAndTouchingBoundaries() {
        LocalDateTime start = LocalDateTime.of(2020, 6, 1, 0, 0);
        LocalDateTime end = start.plusDays(7);
        insert(1, 1, start.minusHours(2), start.plusHours(2));
        insert(2, 1, start.minusDays(2), end.plusDays(2));
        insert(3, 1, end.minusHours(1), end.plusHours(1));
        insert(4, 1, start.minusDays(1), start);
        insert(5, 1, end, end.plusDays(1));
        insert(6, 1, start.plusHours(3), start.plusHours(4));
        insert(7, 1, start.plusHours(4), start.plusHours(5));
        insert(8, 3, start, end);
        insert(9, 2, start, end);
        for (int id = 100; id < 205; id++) {
            insert(id, 1, start.plusDays(3).plusSeconds(id), start.plusDays(4));
        }
        var rows = service.shiftCalendar(new OnCallShiftDtos.CalendarQuery(start, end, null));
        assertThat(rows).hasSize(111);
        assertThat(rows).extracting(OnCallShiftDtos.Shift::id)
                .contains(1L, 2L, 3L, 6L, 7L, 9L, 204L).doesNotContain(4L, 5L, 8L);
        var filtered = service.shiftCalendar(new OnCallShiftDtos.CalendarQuery(start, end, 1L));
        assertThat(filtered).hasSize(110).allMatch(row -> row.scheduleId() == 1);
        assertThat(service.shiftCalendar(new OnCallShiftDtos.CalendarQuery(start, end, 3L))).isEmpty();
        assertThat(service.shiftPage(new OnCallShiftDtos.PageQuery(1, 10, null)).total()).isZero();
    }

    @Test
    void rejectsReverseEmptyAndOverlongCalendarIntervals() {
        for (LocalDateTime end : List.of(future.minusSeconds(1), future, future.plusDays(31).plusSeconds(1))) {
            assertThatThrownBy(() -> service.shiftCalendar(new OnCallShiftDtos.CalendarQuery(future, end, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("日历结束时间");
        }
    }

    @Test
    void httpContractsBindDefaultsAndRejectUnboundedOrInvalidQueries() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ItsmPlatformController(service)).build();
        mvc.perform(get("/api/platform/oncall/shifts/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.records").isEmpty());
        for (int id = 1; id <= 23; id++) {
            insert(id, 1, future.plusMinutes(id), future.plusDays(1));
        }
        mvc.perform(get("/api/platform/oncall/shifts/page").param("pageNum", "3").param("scheduleId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(23))
                .andExpect(jsonPath("$.data.pageNum").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(3))
                .andExpect(jsonPath("$.data.records[0].id").value(21))
                .andExpect(jsonPath("$.data.records[0].scheduleName").value("值班计划 1"));
        mvc.perform(get("/api/platform/oncall/shifts/page").param("pageSize", "1000"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/oncall/shifts/page").param("pageNum", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/oncall/shifts/page").param("scheduleId", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/oncall/shifts/calendar"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/oncall/shifts/calendar")
                        .param("startTime", future.toString()).param("endTime", future.plusDays(7).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(23));
    }

    @Test
    void existingShiftMutationsStillRequireAdminAuthority() {
        ItsmPlatformService securedService = mock(ItsmPlatformService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurityConfiguration.class);
            context.registerBean(ItsmPlatformController.class, () -> new ItsmPlatformController(securedService));
            context.refresh();
            ItsmPlatformController controller = context.getBean(ItsmPlatformController.class);
            var request = new ItsmPlatformController.ShiftRequest(1L, "PRIMARY", 2L, "值班人", future,
                    future.plusDays(1));
            authenticate("USER");
            assertThatThrownBy(() -> controller.addShift(request)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.updateShift(1, request)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.deleteShift(1)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(securedService);
            authenticate("ADMIN");
            controller.addShift(request);
            controller.updateShift(1, request);
            controller.deleteShift(1);
            verify(securedService).addShift(request);
            verify(securedService).updateShift(1, request);
            verify(securedService).deleteShift(1);
        }
    }

    private void insert(long id, long scheduleId, LocalDateTime start, LocalDateTime end) {
        jdbc.update("INSERT INTO oncall_shift VALUES(?,?,?,?,?,?,?)",
                id, scheduleId, "PRIMARY", id, "值班人 " + id, start, end);
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new OpsPrincipal(1, "test-user", "test-token", List.of(role)), "",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
