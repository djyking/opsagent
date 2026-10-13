package com.opsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Same-browser resumption preserves ownership without sharing a public account or renewing
 * indefinitely.
 *
 * @author heyu
 * @since 2026/9/3
 */
@SpringBootTest(
        properties = {
            "ops.security.secret=visitor-experience-test-secret-at-least-32-bytes",
            "ops.auth.demo-enabled=true",
            "spring.datasource.url=jdbc:h2:mem:visitorExperience;MODE=MySQL;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("smoke")
@AutoConfigureMockMvc
class VisitorExperienceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @MockitoBean CaptchaService captcha;

    private record Login(JsonNode data, Cookie cookie) {}

    private Login login(Cookie cookie) throws Exception {
        var request =
                post("/api/auth/login")
                        .secure(true)
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "username",
                                                "user",
                                                "password",
                                                "user",
                                                "captchaId",
                                                "a".repeat(32),
                                                "captchaCode",
                                                "ABCDE")));
        if (cookie != null) request.cookie(cookie);
        var response =
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0))
                        .andReturn()
                        .getResponse();
        Cookie issued = response.getCookie("opsagent_experience");
        assertThat(issued).isNotNull();
        assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.getSecure()).isTrue();
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Strict");
        return new Login(json.readTree(response.getContentAsString()).path("data"), issued);
    }

    private long userId(Login login) throws Exception {
        var response =
                mvc.perform(
                                get("/api/auth/me")
                                        .header(
                                                "Authorization",
                                                "Bearer "
                                                        + login.data()
                                                                .path("accessToken")
                                                                .asText()))
                        .andExpect(jsonPath("$.code").value(0))
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("userId").asLong();
    }

    @Test
    void returnsSameOwnerAfterLogoutButIsolatesOtherBrowserAndStoresOnlyDigest() throws Exception {
        Login first = login(null);
        long owner = userId(first);
        assertThat(owner).isNegative();
        assertThat(first.data().path("refreshToken").asText()).isEmpty();
        assertThat(
                        Duration.between(
                                        Instant.now(),
                                        Instant.parse(first.data().path("expiresAt").asText()))
                                .toMinutes())
                .isBetween(29L, 30L);
        assertThat(Duration.between(Instant.now(), auth.actor(owner).expiresAt()).toHours())
                .isEqualTo(23L);
        String stored =
                jdbc.queryForObject(
                        "SELECT credential_hash FROM visitor_experience WHERE user_id=?",
                        String.class,
                        owner);
        assertThat(stored).hasSize(64).isNotEqualTo(first.cookie().getValue());
        mvc.perform(
                        post("/api/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + first.data().path("accessToken").asText()))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(auth.actor(owner).active()).isTrue();
        Login resumed = login(first.cookie());
        assertThat(userId(resumed)).isEqualTo(owner);
        assertThat(resumed.data().path("visitorExperience").asText()).isEqualTo("RESUMED");
        assertThat(resumed.data().path("sessionExpiresAt").asText())
                .isEqualTo(first.data().path("sessionExpiresAt").asText());
        assertThat(userId(login(null))).isNotEqualTo(owner);
        assertThat(userId(login(new Cookie("opsagent_experience", Long.toString(owner)))))
                .isNotEqualTo(owner);
    }

    @Test
    void explicitTerminationRevokesOwnerAndDoesNotReviveOldIdentity() throws Exception {
        Login first = login(null);
        long owner = userId(first);
        mvc.perform(
                        post("/api/auth/end-experience")
                                .header(
                                        "Authorization",
                                        "Bearer " + first.data().path("accessToken").asText()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(auth.actor(owner).active()).isFalse();
        assertThat(auth.actor(owner).reasonCode()).isEqualTo("VISITOR_REVOKED");
        assertThat(auth.actor(owner).revokedAt()).isNotNull();
        Login next = login(first.cookie());
        assertThat(next.data().path("visitorExperience").asText()).isEqualTo("ENDED");
        assertThat(userId(next)).isNotEqualTo(owner);
    }

    @Test
    void expiredExperienceCannotBeExtendedOrClaimedAgain() throws Exception {
        Login first = login(null);
        long owner = userId(first);
        jdbc.update(
                "UPDATE visitor_experience SET expires_at=? WHERE user_id=?",
                java.time.LocalDateTime.ofInstant(
                        Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC),
                owner);
        jdbc.update(
                "UPDATE visitor_lease SET expires_at=? WHERE user_id=?",
                java.time.LocalDateTime.ofInstant(
                        Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC),
                owner);
        assertThat(auth.actor(owner).reasonCode()).isEqualTo("VISITOR_LEASE_EXPIRED");
        Login next = login(first.cookie());
        assertThat(next.data().path("visitorExperience").asText()).isEqualTo("EXPIRED");
        assertThat(userId(next)).isNotEqualTo(owner);
    }
}
