package com.opsagent.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

/**
 * 验证方法级权限异常优先于通用异常处理，且不泄漏异常细节。
 *
 * @author heyu
 * @since 2026/9/3
 */
class MethodSecurityExceptionHandlerTest {
    @Test
    void authorizationDenialRemainsForbiddenDespiteGenericAdvice() throws Exception {
        MockMvcBuilders.standaloneSetup(new DeniedController())
                .setControllerAdvice(new GenericAdvice(), new MethodSecurityExceptionHandler())
                .build()
                .perform(get("/admin-only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value("无权访问该资源"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @RestController
    static class DeniedController {
        @GetMapping("/admin-only")
        String denied() {
            throw new AuthorizationDeniedException("private authorization details");
        }
    }

    @RestControllerAdvice
    static class GenericAdvice {
        @ExceptionHandler(Exception.class)
        String unexpected(Exception exception) {
            return "generic system error";
        }
    }
}
