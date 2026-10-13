package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/**
 * 仅支持隔离实验服务的健康读取、固定故障注入与恢复；没有命令执行或Docker访问能力。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class OperationsLabClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${ops.operations.lab-url:}")
    private String labUrl;

    @Value("${ops.operations.lab-token:}")
    private String labToken;

    OperationsLabClient(ObjectMapper json) {
        this.json = json;
    }

    boolean configured() {
        return labUrl != null && !labUrl.isBlank() && labToken != null && labToken.length() >= 32;
    }

    Probe health() {
        return request("health", false);
    }

    void fault() {
        request("fault", true);
    }

    void recover() {
        request("recover", true);
    }

    private Probe request(String action, boolean mutate) {
        if (!configured()) throw new LabFailure("NOT_CONFIGURED", "隔离演练服务尚未配置。");
        try {
            URI uri = URI.create(labUrl.replaceAll("/+$", "") + "/" + action);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new LabFailure("INVALID_CONFIGURATION", "固定实验地址配置不符合HTTP访问范围要求。");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3));
            if (mutate) builder.header("X-Lab-Token", labToken).POST(HttpRequest.BodyPublishers.noBody());
            else builder.GET();
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.body().length() > 4096) throw new LabFailure("INVALID_RESPONSE", "实验响应超过允许大小。");
            JsonNode body = json.readTree(response.body());
            if (!"ISOLATED_LAB".equals(body.path("scope").asText())) {
                throw new LabFailure("SCOPE_MISMATCH", "目标响应没有声明预期的ISOLATED_LAB范围。");
            }
            if (mutate && response.statusCode() != 200) {
                throw new LabFailure("ACTION_REJECTED", "实验服务操作未成功，实际HTTP " + response.statusCode());
            }
            if (!mutate && response.statusCode() != 200 && response.statusCode() != 503) {
                throw new LabFailure("UNEXPECTED_HTTP", "实验健康请求返回意外HTTP " + response.statusCode());
            }
            String status = body.path("status").asText();
            if (!"UP".equals(status) && !"DOWN".equals(status)) {
                throw new LabFailure("INVALID_RESPONSE", "实验服务没有返回UP或DOWN白名单状态。");
            }
            return new Probe(response.statusCode(), status, Instant.now());
        } catch (LabFailure exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new LabFailure("TIMEOUT", "实验服务请求超过3秒超时限制。");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LabFailure("INTERRUPTED", "实验服务请求被执行线程中断。");
        } catch (Exception exception) {
            throw new LabFailure("CONNECTION_OR_RESPONSE_ERROR", "实验连接或响应解析失败；异常类型="
                    + exception.getClass().getSimpleName());
        }
    }

    /** @author heyu */
    static final class LabFailure extends RuntimeException {
        private final String code;
        private final String safeEvidence;

        LabFailure(String code, String safeEvidence) {
            super(code);
            this.code = code;
            this.safeEvidence = safeEvidence;
        }

        String evidence() {
            return "reasonCode=" + code + "；" + safeEvidence;
        }
    }

    /** @author heyu */
    record Probe(int httpStatus, String status, Instant observedAt) {
        String evidence() {
            return "scope=ISOLATED_LAB；HTTP " + httpStatus + "；状态=" + status + "；采集=" + observedAt;
        }
    }
}
