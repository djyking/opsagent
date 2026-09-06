package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * 向工单服务验证原请求用户的读取权限，避免把客户端 ticketId 当成授权。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class TicketAccessClient {
    private final String configuredUrl;
    private final DiscoveryClient discovery;
    private final RestClient client;

    TicketAccessClient(@Value("${ops.knowledge.ticket-url:}") String configuredUrl,
                       DiscoveryClient discovery, RestClient.Builder builder) {
        this.configuredUrl = configuredUrl;
        this.discovery = discovery;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.clone().requestFactory(factory).build();
    }

    void requireVisible(long ticketId) {
        if (ticketId < 1) throw new BusinessException(ErrorCode.VALIDATION, "工单编号无效");
        JsonNode response = read("/api/tickets/" + ticketId);
        if (response.path("data").path("id").asLong() != ticketId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "工单不存在或当前账号不可访问");
        }
    }

    Set<Long> visibleTicketIds() {
        // Internal Agent tokens are audience-bound, not end-user JWTs. Global retrieval must
        // not promote them into user credentials; linked evidence remains in scoped ticket tools.
        if (SecurityUsers.current().tokenId().startsWith("internal:")) return Set.of();
        JsonNode data = read("/api/tickets").path("data");
        if (!data.isArray()) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限暂时无法验证，请稍后重试");
        }
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : data) {
            if (!row.path("id").canConvertToLong() || row.path("id").asLong() < 1) {
                throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限暂时无法验证，请稍后重试");
            }
            ids.add(row.path("id").asLong());
        }
        return Set.copyOf(ids);
    }

    private JsonNode read(String path) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes request)
                || request.getRequest().getHeader("Authorization") == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "请重新登录后访问工单附件");
        }
        try {
            String endpoint = configuredUrl;
            if (endpoint == null || endpoint.isBlank()) {
                var instances = discovery.getInstances("ops-ticket-service");
                if (instances.isEmpty()) throw new IllegalStateException("Ticket service unavailable");
                endpoint = instances.get(0).getUri().toString();
            }
            URI base = URI.create(endpoint);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getUserInfo() != null) {
                throw new IllegalStateException("Invalid ticket service configuration");
            }
            var call = client.get().uri(endpoint.replaceAll("/+$", "") + path)
                    .header("Authorization", request.getRequest().getHeader("Authorization"));
            String trace = request.getRequest().getHeader("X-Trace-Id");
            if (trace != null) call.header("X-Trace-Id", trace);
            JsonNode response = call.retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt(-1) != 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "工单不存在或当前账号不可访问");
            }
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "工单不存在或当前账号不可访问");
            }
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限暂时无法验证，请稍后重试");
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限暂时无法验证，请稍后重试");
        }
    }
}
