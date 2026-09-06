package com.opsagent.auth;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 由认证服务复核异步运行当前主体，不复用登录令牌或采信外部角色头。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/internal/agent/actors")
class InternalActorController {
    private final AuthService auth;
    private final InternalActorTokens tokens;

    InternalActorController(
            AuthService auth,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret) {
        this.auth = auth;
        this.tokens = new InternalActorTokens(secret);
    }

    @GetMapping("/{userId}")
    ApiResponse<AuthService.ActorView> actor(
            @RequestHeader("Authorization") String authorization, @PathVariable long userId) {
        InternalActorTokens.Context ctx = tokens.verify(authorization, "auth");
        if (ctx.userId() != userId)
            throw new BusinessException(ErrorCode.FORBIDDEN, "运行主体与查询主体不一致");
        return ApiResponse.success(auth.actor(userId));
    }
}
