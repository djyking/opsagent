package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.SecurityUsers;

import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 通过真实 Auth HTTP 边界验证角色撤销、访客租约、接收服务和线程身份恢复。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalActorAccessTest {
    private static final String SECRET = "actor-access-test-secret-more-than-32-bytes";
    private final InternalActorTokens tokens = new InternalActorTokens(SECRET);
    private MockWebServer auth;
    private InternalActorAccess access;

    @BeforeEach
    void setup() throws IOException {
        auth = new MockWebServer();
        auth.start();
        access = new InternalActorAccess(tokens, auth.url("/").toString());
    }

    @AfterEach
    void close() throws IOException {
        SecurityContextHolder.clearContext();
        auth.shutdown();
    }

    @Test
    void shouldRevalidateRolesForEveryRequestAndRestorePreviousThreadContext() throws Exception {
        var original =
                new InternalActorTokens.Context(
                        7,
                        "old-name",
                        List.of("ADMIN", "USER"),
                        "run",
                        "demo-order",
                        Instant.now().plusSeconds(600));
        String header = "Bearer " + tokens.issue("rag", original);
        auth.enqueue(
                NativeToolModelClientTest.json(
                        """
                        {"code":0,"data":{"active":true,"userId":7,"username":"current-name",
                         "roles":["USER"],"expiresAt":null}}
                        """));
        var verified = access.verify(header, "rag");
        assertThat(verified.roles()).containsExactly("USER");
        assertThat(verified.username()).isEqualTo("current-name");
        var outbound = auth.takeRequest();
        assertThat(outbound.getPath()).isEqualTo("/internal/agent/actors/7");
        assertThat(tokens.verify(outbound.getHeader("Authorization"), "auth").userId())
                .isEqualTo(7);
        var before = SecurityContextHolder.getContext();
        assertThatThrownBy(
                        () -> {
                            try (var scope = InternalActorAccess.open(verified)) {
                                assertThat(SecurityUsers.current().roles()).containsExactly("USER");
                                throw new IllegalStateException("simulated business failure");
                            }
                        })
                .isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext()).isSameAs(before);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        auth.enqueue(
                NativeToolModelClientTest.json(
                        "{\"code\":0,\"data\":{\"active\":false,\"userId\":7}}"));
        assertThatThrownBy(() -> access.verify(header, "rag"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ACTOR_INACTIVE");
        assertThat(auth.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectAbsentExpiredOrMismatchedGuestLeaseAndNeverAcceptWrongAudience() {
        var guest =
                new InternalActorTokens.Context(
                        -55,
                        "visitor",
                        List.of("DEMO"),
                        "run",
                        "demo-order",
                        Instant.now().plusSeconds(600));
        String header = "Bearer " + tokens.issue("rag", guest);
        auth.enqueue(
                NativeToolModelClientTest.json(
                        """
                        {"code":0,"data":{"active":true,"userId":-55,"username":"visitor",
                         "roles":["DEMO"],"expiresAt":null}}
                        """));
        assertThatThrownBy(() -> access.verify(header, "rag"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ACTOR_LEASE_REQUIRED");
        auth.enqueue(
                NativeToolModelClientTest.json(
                        """
                        {"code":0,"data":{"active":true,"userId":-55,"username":"visitor","roles":["DEMO"],
                         "expiresAt":"2000-01-01T00:00:00Z"}}
                        """));
        assertThatThrownBy(() -> access.verify(header, "rag"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ACTOR_LEASE_EXPIRED");
        auth.enqueue(
                NativeToolModelClientTest.json(
                        """
                        {"code":0,"data":{"active":true,"userId":1,"username":"admin",
                         "roles":["ADMIN"],"expiresAt":null}}
                        """));
        assertThatThrownBy(() -> access.verify(header, "rag"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ACTOR_ID_MISMATCH");
        assertThatThrownBy(() -> access.verify(header, "ticket"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("INTERNAL_AUTH_INVALID");
        assertThat(auth.getRequestCount()).isEqualTo(3);
    }
}
