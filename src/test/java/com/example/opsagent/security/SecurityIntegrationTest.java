package com.example.opsagent.security;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import com.example.opsagent.security.jwt.OpsTokenProperties;
import com.example.opsagent.security.jwt.OpsTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证登录 Filter、JWT 恢复、白名单和 401 响应的完整请求链。
 *
 * @author heyu
 * @since 2026/8/15
 */
@SpringBootTest(properties = {
    "ops-agent.security.jwt.secret=ops-agent-test-secret-that-is-at-least-32-bytes-long",
    "management.health.redis.enabled=false",
    "management.health.db.enabled=false"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private SysUserDao sysUserDao;

    private final AtomicReference<SysUser> currentUser = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        currentUser.set(user("enable"));
        when(sysUserDao.selectOne(any())).thenAnswer(invocation -> currentUser.get());
    }

    @Test
    void shouldLoginAndUseJwtToReadCurrentUser() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(1))
            .andExpect(jsonPath("$.data.username").value("alice"))
            .andExpect(jsonPath("$.data.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void shouldReturn401ForWrongPasswordAndMissingUserWithoutLeakingDifference() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        currentUser.set(null);
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"missing\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void shouldRejectBlankLoginRequestAndDisabledUser() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());

        currentUser.set(user("disable"));
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldProtectEndpointAndRejectInvalidJwt() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectExpiredJwtAndDisabledUserOldJwt() throws Exception {
        OpsUserPrincipal principal = principal("enable");
        OpsTokenProperties expiredProperties = tokenProperties(-1);
        String expiredToken = new OpsTokenService(expiredProperties).generateToken(principal);
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized());

        OpsTokenProperties validProperties = tokenProperties(120);
        String oldToken = new OpsTokenService(validProperties).generateToken(principal);
        currentUser.set(user("disable"));
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowWhitelistedHealthEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    private String loginAndGetToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"correct-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("accessToken").asText();
    }

    private SysUser user(String status) {
        SysUser user = new SysUser();
        user.setUsername("alice");
        user.setId(1L);
        user.setPassword(passwordEncoder.encode("correct-password"));
        user.setDisplayName("Alice");
        user.setStatus(status);
        user.setDeleted(0);
        return user;
    }

    private OpsUserPrincipal principal(String status) {
        return new OpsUserPrincipal(1L, "alice", "unused", "Alice", status,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private OpsTokenProperties tokenProperties(long expireMinutes) {
        OpsTokenProperties properties = new OpsTokenProperties();
        properties.setSecret("ops-agent-test-secret-that-is-at-least-32-bytes-long");
        properties.setExpireMinutes(expireMinutes);
        return properties;
    }
}
