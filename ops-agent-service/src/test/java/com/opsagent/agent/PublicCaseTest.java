package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证公开案例只读投影与私有记录隔离。
 *
 * @author heyu
 * @since 2026/9/8
 */
class PublicCaseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedCallerCannotListHistoricalCases() throws Exception {
        var service = new PublicCaseService(mapper);
        assertThrows(BusinessException.class, service::list);
    }

    @Test
    void differentVisitorsSeeSameCuratedSnapshotWithoutPrivateRecordIdentifiers() throws Exception {
        var service = new PublicCaseService(mapper);
        authenticate(-11);
        var first = mapper.valueToTree(service.list());
        authenticate(-22);
        assertEquals(first, mapper.valueToTree(service.list()));
        assertFalse(first.isEmpty());
        var fields = new HashSet<String>();
        first.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "title", "scenarioCode", "targetCode", "environment", "recordedAt",
                "summary", "result", "confirmation", "source", "evidence", "steps", "limitation"), fields);
        var content = mapper.writeValueAsString(first);
        assertFalse(content.contains("/tickets/"));
        assertFalse(content.contains("/runs/"));
        assertFalse(content.contains("d3cff73c-f7b9-4831-b35d-419091bac17c"));
        assertFalse(content.contains("creatorId"));
        assertFalse(content.contains("ownerId"));
        assertTrue(first.get(0).get("confirmation").asText().contains("尚未记录"));
        assertTrue(first.get(0).get("limitation").asText().contains("历史只读"));
        assertTrue(first.get(0).get("steps").size() >= 4);
    }

    @Test
    void publicControllerCannotFetchPrivateTicketOrRunOrPerformMutations() throws Exception {
        authenticate(-11);
        var mvc = MockMvcBuilders.standaloneSetup(new PublicCaseController(new PublicCaseService(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/automation/public-cases"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/automation/public-cases/2078"))
                .andExpect(jsonPath("$.code").value(40400));
        mvc.perform(get("/api/automation/public-cases/d3cff73c-f7b9-4831-b35d-419091bac17c"))
                .andExpect(jsonPath("$.code").value(40400));
        MockMvcBuilders.standaloneSetup(new PublicCaseController(new PublicCaseService(mapper))).build()
                .perform(post("/api/automation/public-cases/cloud-config-recovery-20260908"))
                .andExpect(status().isMethodNotAllowed());
    }

    private static void authenticate(long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new OpsPrincipal(id, "visitor", "visitor", List.of("DEMO")), null, List.of()));
    }
}
