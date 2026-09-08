package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ConfigurationExecutionEvidence;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Authenticated proxy to the independent registered-file executor; no caller paths or commands.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class FileConfigurationClient {
    private final URI base;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    FileConfigurationClient(
            @Value("${ops.configuration.executor.url:http://127.0.0.1:18110}") String url,
            @Value("${ops.configuration.executor.secret:}") String secret,
            @Value("${ops.configuration.executor.trusted-host:}") String trustedHost,
            ObjectMapper json) {
        this(
                url,
                secret,
                trustedHost,
                json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    FileConfigurationClient(String url, String secret, ObjectMapper json) {
        this(
                url,
                secret,
                "",
                json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    FileConfigurationClient(String url, String secret, ObjectMapper json, HttpClient http) {
        this(url, secret, "", json, http);
    }

    FileConfigurationClient(
            String url, String secret, String trustedHost, ObjectMapper json, HttpClient http) {
        this.base = trusted(url, trustedHost);
        this.secret = secret;
        this.json = json;
        this.http = http;
    }

    JsonNode get(String path) {
        return request("GET", path, null);
    }

    JsonNode post(String path, JsonNode body) {
        return request("POST", path, body);
    }

    private JsonNode request(String method, String path, JsonNode body) {
        var actor = SecurityUsers.current();
        boolean admin =
                actor.roles().stream()
                        .anyMatch(role -> Set.of("ADMIN", "ROLE_ADMIN").contains(role));
        boolean ops =
                actor.roles().stream().anyMatch(role -> Set.of("OPS", "ROLE_OPS").contains(role));
        if (actor.userId() <= 0 || !admin && (!ops || method.equals("POST")))
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前身份不允许执行该配置动作");
        if (secret.length() < 32 || base == null)
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "独立配置执行器尚未配置或信任身份未接入");
        if (!path.matches("/(files|drafts|tasks)(/[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}){0,2}"))
            throw new BusinessException(ErrorCode.VALIDATION, "配置对象标识不合法");
        try {
            var builder =
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(Duration.ofSeconds(20))
                            .header("X-Ops-Executor-Token", secret)
                            .header("X-Ops-Actor-Id", String.valueOf(actor.userId()))
                            .header("X-Ops-Actor-Role", admin ? "ADMIN" : "OPS")
                            .header("Accept", "application/json");
            if (method.equals("POST")
                    && (path.endsWith("/execute") || path.endsWith("/verify"))
                    && RequestContextHolder.getRequestAttributes()
                            instanceof ServletRequestAttributes attributes) {
                String authorization = attributes.getRequest().getHeader("Authorization");
                if (authorization != null
                        && authorization.startsWith("Bearer ")
                        && authorization.length() < 16384)
                    builder.header("X-Ops-User-Authorization", authorization);
            }
            if (method.equals("POST")) {
                if (body == null || !body.isObject())
                    throw new BusinessException(ErrorCode.VALIDATION, "配置请求必须为对象");
                byte[] bytes = json.writeValueAsBytes(body);
                if (bytes.length > 1048576)
                    throw new BusinessException(ErrorCode.VALIDATION, "配置请求超过允许大小");
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            } else builder.GET();
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) {
                bytes = stream.readNBytes(4194305);
            }
            if (bytes.length > 4194304)
                throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "执行器响应超出允许范围");
            JsonNode result = json.readTree(bytes);
            if (response.statusCode() != 200 || result.path("code").asInt(-1) != 0) {
                int status = response.statusCode();
                String message = result.path("message").asText("配置执行器暂不可用");
                if (message.length() > 500) message = "配置执行器返回无效错误信息";
                if (status == 400) throw new BusinessException(ErrorCode.VALIDATION, message);
                if (status == 403) throw new BusinessException(ErrorCode.FORBIDDEN, message);
                if (status == 404) throw new BusinessException(ErrorCode.NOT_FOUND, message);
                if (status == 409) throw new BusinessException(ErrorCode.CONFLICT, message);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
            }
            return result.path("data");
        } catch (BusinessException | ResponseStatusException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "执行器请求中断，请用原任务编号查询结果");
        } catch (Exception failure) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "执行器暂不可用，请保留草稿并查询原任务，避免重复操作");
        }
    }

    static URI trusted(String value) {
        return trusted(value, "");
    }

    static URI trusted(String value, String trustedHost) {
        try {
            URI uri = URI.create(value);
            return "http".equals(uri.getScheme())
                            && (Set.of("localhost", "127.0.0.1", "[::1]", "::1")
                                            .contains(uri.getHost())
                                    || ConfigurationExecutionEvidence.privateAddress(trustedHost)
                                            && trustedHost.equals(uri.getHost()))
                            && uri.getUserInfo() == null
                            && uri.getQuery() == null
                            && uri.getFragment() == null
                            && (uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    ? uri
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
