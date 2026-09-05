package com.opsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
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
        mvc.perform(post("/api/auth/login").contentType("application/json").content(
                mapper.writeValueAsString(Map.of("username", "round5_registration", "password", "test-password-only"))))
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
}
