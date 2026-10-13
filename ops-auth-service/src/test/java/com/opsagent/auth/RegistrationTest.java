package com.opsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证注册、密码存储、固定普通用户权限和重复账号保护。
 * @author heyu
 * @since 2026/9/3
 */
@SpringBootTest(properties = "ops.security.secret=registration-test-only-secret-at-least-32-bytes")
@ActiveProfiles("smoke")
@AutoConfigureMockMvc
class RegistrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean CaptchaImageGenerator captchaImages;

    @BeforeEach
    void setUpCaptcha() {
        new CaptchaRedisFixture(redis);
        when(captchaImages.generate()).thenReturn(new CaptchaImageGenerator.Generated(
                "7Q2KM", new CaptchaImageGenerator().generate().imageDataUrl()));
    }

    private String captchaId() throws Exception {
        String json = mvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andExpect(jsonPath("$.data.imageDataUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("captchaId").asText();
    }

    @Test
    void publicRegistrationStoresHashAndOnlyAssignsUserRole() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "username", " round5_registration ", "password", "test-password-only",
                "displayName", "注册验收", "roles", new String[]{"ADMIN"}));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        String stored = jdbc.queryForObject("SELECT password FROM sys_user WHERE username=?",
                String.class, "round5_registration");
        assertThat(stored).isNotEqualTo("test-password-only");
        assertThat(encoder.matches("test-password-only", stored)).isTrue();
        assertThat(jdbc.queryForList("SELECT r.code FROM sys_user u JOIN sys_user_role ur ON u.id=ur.user_id"
                + " JOIN sys_role r ON r.id=ur.role_id WHERE u.username=?",
                String.class, "round5_registration")).containsExactly("USER");
        String challenge = captchaId();
        String loginBody = mapper.writeValueAsString(Map.of("username", "round5_registration",
                "password", "test-password-only", "captchaId", challenge, "captchaCode", "7Q2KM"));
        String login = mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginBody))
                .andExpect(jsonPath("$.code").value(40000));
        String refreshToken = mapper.readTree(login).path("data").path("refreshToken").asText();
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(
                        mapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(40900));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",
                Integer.class, "round5_registration")).isEqualTo(1);
    }

    @Test
    void rejectsShortPasswordsAndBcryptByteOverflow() throws Exception {
        for (String password : new String[]{"short", "密".repeat(25)}) {
            mvc.perform(post("/api/auth/register").contentType("application/json").content(
                    mapper.writeValueAsString(Map.of("username", "invalid_registration", "password", password))))
                    .andExpect(jsonPath("$.code").value(40000));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",
                Integer.class, "invalid_registration")).isZero();
    }

    @Test
    void loginRequiresCaptchaAndRejectsWrongOrExpiredValues() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(
                        mapper.writeValueAsString(Map.of("username", "admin", "password", "anything"))))
                .andExpect(jsonPath("$.code").value(40000));
        String challenge = captchaId();
        for (String answer : new String[]{"WRONG", "7Q2KM"}) {
            mvc.perform(post("/api/auth/login").contentType("application/json").content(
                            mapper.writeValueAsString(Map.of("username", "admin", "password", "anything",
                                    "captchaId", challenge, "captchaCode", answer))))
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.message").value("验证码错误或已过期，请换一张后重试"));
        }
    }
}
