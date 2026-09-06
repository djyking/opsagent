package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界 SSE 连接读取持久化事件，重连时 Last-Event-ID 继续补发。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class AgentEventStream {
    private final AgentStore store;
    private final AgentClients clients;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    AgentEventStream(AgentStore store, AgentClients clients) {
        this.store = store;
        this.clients = clients;
    }

    @GetMapping(value = "/api/automation/runs/{id}/stream", produces = "text/event-stream")
    synchronized SseEmitter connect(
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after) {
        Context actor = clients.current(SecurityUsers.current(), "event-stream");
        if (store.get(id).owner() != actor.userId() && !actor.roles().contains("ADMIN"))
            throw AgentClients.denied();
        if (after < 0 || streams.size() >= 32) throw AgentJson.invalid("事件连接数量或游标无效");
        String key = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(25000L);
        streams.put(key, new Stream(id, after, emitter, Instant.now().plusSeconds(20), actor));
        emitter.onCompletion(() -> streams.remove(key));
        emitter.onTimeout(() -> streams.remove(key));
        emitter.onError(error -> streams.remove(key));
        return emitter;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handshakeFailure(BusinessException exception) {
        int status =
                switch (exception.getErrorCode()) {
                    case UNAUTHENTICATED -> 401;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case VALIDATION -> 400;
                    case MIDDLEWARE_UNAVAILABLE -> 503;
                    case SYSTEM_ERROR -> 500;
                };
        // A strict SSE Accept header must not prevent a JSON error before the stream opens.
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(exception.getErrorCode().code(), exception.getMessage()));
    }

    @Scheduled(fixedDelay = 1500)
    void send() {
        streams.forEach(
                (key, stream) -> {
                    try {
                        Context actor = clients.refresh(stream.actor);
                        if (Instant.now().isAfter(stream.until)
                                || !actor.validUntil().isAfter(Instant.now())
                                || store.get(stream.run).owner() != actor.userId()
                                        && !actor.roles().contains("ADMIN")) {
                            stream.emitter.complete();
                            streams.remove(key);
                            return;
                        }
                        for (JsonNode event : store.events(stream.run, stream.after)) {
                            stream.emitter.send(
                                    SseEmitter.event()
                                            .id(event.path("id").asText())
                                            .name("run-event")
                                            .data(event));
                            stream.after = event.path("id").asLong();
                        }
                        stream.emitter.send(SseEmitter.event().comment("heartbeat"));
                        if (Instant.now().isAfter(stream.until)) {
                            stream.emitter.complete();
                            streams.remove(key);
                        }
                    } catch (Exception exception) {
                        streams.remove(key);
                        stream.emitter.completeWithError(exception);
                    }
                });
    }

    /**
     * @author heyu
     */
    private static final class Stream {
        private final String run;
        private long after;
        private final SseEmitter emitter;
        private final Instant until;
        private final Context actor;

        private Stream(String run, long after, SseEmitter emitter, Instant until, Context actor) {
            this.run = run;
            this.after = after;
            this.emitter = emitter;
            this.until = until;
            this.actor = actor;
        }
    }
}
