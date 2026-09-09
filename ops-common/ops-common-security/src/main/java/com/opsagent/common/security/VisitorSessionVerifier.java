package com.opsagent.common.security;

import java.time.Instant;

/**
 * Each public visitor request verifies the current experience lease independently of JWT expiry.
 *
 * @author heyu
 */
@FunctionalInterface
public interface VisitorSessionVerifier {
    void verify(OpsPrincipal principal);

    static VisitorSessionVerifier remote(String secret, String authUrl) {
        var tokens = new InternalActorTokens(secret);
        var access = new InternalActorAccess(tokens, authUrl);
        return principal -> {
            var context =
                    new InternalActorTokens.Context(
                            principal.userId(),
                            principal.username(),
                            principal.roles(),
                            "visitor-request",
                            "visitor-session",
                            Instant.now().plusSeconds(30));
            access.verify("Bearer " + tokens.issue("auth", context), "auth");
        };
    }
}
