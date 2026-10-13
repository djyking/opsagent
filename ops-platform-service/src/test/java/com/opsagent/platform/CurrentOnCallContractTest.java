package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 约束首页与排班页共同使用的当前值班响应字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
class CurrentOnCallContractTest {
    @Test
    void currentMembersRemainNestedInTheResponse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("ops-ticket-service"), eq("ops-ticket-service")))
                .thenReturn(List.of(Map.of("roleType", "PRIMARY", "userName", "当班人员", "userId", 2)));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            ItsmPlatformRepository repository = new ItsmPlatformRepository(jdbc, metrics);
            JsonNode json = new ObjectMapper().valueToTree(repository.currentOnCall(" ops-ticket-service "));

            assertFalse(json.path("fallback").asBoolean());
            assertEquals("当班人员", json.path("members").get(0).path("userName").asText());
            assertEquals("PRIMARY", json.path("members").get(0).path("roleType").asText());
            assertFalse(json.has("userName"));
        } finally {
            metrics.close();
        }
    }

    @Test
    void noActiveShiftReturnsAnExplicitFallbackAndEmptyMembers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(""), eq(""))).thenReturn(List.of());
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            ItsmPlatformRepository repository = new ItsmPlatformRepository(jdbc, metrics);
            JsonNode json = new ObjectMapper().valueToTree(repository.currentOnCall(null));

            assertTrue(json.path("fallback").asBoolean());
            assertTrue(json.path("members").isArray());
            assertTrue(json.path("members").isEmpty());
            assertFalse(json.path("message").asText().isBlank());
            assertEquals(1.0, metrics.counter("opsagent.oncall.miss").count());
        } finally {
            metrics.close();
        }
    }
}
