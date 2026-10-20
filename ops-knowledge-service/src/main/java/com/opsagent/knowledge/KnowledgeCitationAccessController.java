package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Revalidates stored retrieval references against current domain ACLs without retrieving content.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class KnowledgeCitationAccessController {
    private final KnowledgeService service;
    private final JdbcTemplate jdbc;
    private final InternalActorAccess access;

    KnowledgeCitationAccessController(
            KnowledgeService service,
            JdbcTemplate jdbc,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String authUrl) {
        this.service = service;
        this.jdbc = jdbc;
        access = new InternalActorAccess(new InternalActorTokens(secret), authUrl);
    }

    @PostMapping("/internal/agent/validate-citations")
    ApiResponse<Verdict> validate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody Request request) {
        var actor = access.verify(authorization, "knowledge");
        try (var scope = InternalActorAccess.open(actor)) {
            try {
                for (var citation : request.citations()) {
                    var document = service.readableDocument(citation.documentId());
                    if ((!request.allowUnpublished()
                                    && !"PUBLISHED".equals(document.get("review_status")))
                            || "ARCHIVED".equals(document.get("review_status"))
                            || !(document.get("version") instanceof Number version)
                            || version.intValue() != citation.version()) return denied();
                    Integer count =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM knowledge_chunk WHERE id=? AND"
                                            + " document_id=?",
                                    Integer.class,
                                    citation.chunkId(),
                                    citation.documentId());
                    if (count == null || count != 1) return denied();
                }
                return ApiResponse.success(new Verdict(true));
            } catch (RuntimeException denied) {
                return denied();
            }
        }
    }

    private ApiResponse<Verdict> denied() {
        return ApiResponse.success(new Verdict(false));
    }

    /**
     * @author heyu @since 2026/9/3
     */
    record Citation(@Min(1) long documentId, @Min(1) long chunkId, @Min(1) int version) {}

    /**
     * @author heyu @since 2026/9/3
     */
    record Request(
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Citation> citations,
            boolean allowUnpublished) {
        Request(List<Citation> citations) {
            this(citations, false);
        }
    }

    /**
     * @author heyu @since 2026/9/3
     */
    record Verdict(boolean allowed) {}
}
