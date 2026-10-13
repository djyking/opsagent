package com.opsagent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证随机图片、答案不外泄、一次性消费、限流和故障关闭边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class CaptchaServiceTest {
    private static final String ANSWER = "7Q2KM";
    private StringRedisTemplate redis;
    private CaptchaImageGenerator generator;
    private CaptchaRedisFixture fixture;
    private CaptchaService captcha;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        generator = mock(CaptchaImageGenerator.class);
        when(generator.generate()).thenReturn(
                new CaptchaImageGenerator.Generated(ANSWER, "data:image/png;base64,test"));
        fixture = new CaptchaRedisFixture(redis);
        captcha = new CaptchaService(redis, generator, 60, 1000);
    }

    @Test
    void imageGeneratorUsesAnUnambiguousAlphabetAndProducesARealPng() throws Exception {
        var generated = new CaptchaImageGenerator().generate();
        assertThat(generated.answer()).matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}");
        byte[] png = Base64.getDecoder().decode(generated.imageDataUrl().split(",", 2)[1]);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(192);
        assertThat(image.getHeight()).isEqualTo(64);
    }

    @Test
    void issueStoresOnlyASaltedDigestWithTtlAndDoesNotExposeTheAnswer() throws Exception {
        var first = captcha.issue("127.0.0.1");
        var second = captcha.issue("127.0.0.1");
        assertThat(first.captchaId()).matches("[a-f0-9]{32}").isNotEqualTo(second.captchaId());
        assertThat(first.expiresInSeconds()).isEqualTo(120);
        assertThat(fixture.stored).hasSize(2);
        assertThat(fixture.stored.values()).allSatisfy(value -> assertThat(value).matches("[a-f0-9]{64}"));
        assertThat(fixture.ttl.values()).containsOnly(Duration.ofSeconds(120));
        String json = new ObjectMapper().writeValueAsString(first);
        assertThat(json).doesNotContain(ANSWER, "answer", "digest");
    }

    @Test
    void correctAnswerIsCaseInsensitiveAndCannotBeReplayed() {
        var challenge = captcha.issue("local");
        captcha.verify(challenge.captchaId(), " 7q2km ");
        assertThat(fixture.stored).isEmpty();
        assertThatThrownBy(() -> captcha.verify(challenge.captchaId(), ANSWER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("验证码错误或已过期");
    }

    @Test
    void wrongExpiredMissingAndMalformedAnswersCannotBeUsedToLogin() {
        var challenge = captcha.issue("local");
        assertThatThrownBy(() -> captcha.verify(challenge.captchaId(), "WRONG"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> captcha.verify(challenge.captchaId(), ANSWER))
                .isInstanceOf(BusinessException.class);
        var expired = captcha.issue("local");
        fixture.stored.clear();
        assertThatThrownBy(() -> captcha.verify(expired.captchaId(), ANSWER))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> captcha.verify(null, ANSWER)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> captcha.verify("invalid-id", ANSWER)).isInstanceOf(BusinessException.class);
        var blank = captcha.issue("local");
        assertThatThrownBy(() -> captcha.verify(blank.captchaId(), null)).isInstanceOf(BusinessException.class);
        assertThat(fixture.stored).isEmpty();
    }

    @Test
    void onlyOneConcurrentVerificationCanConsumeTheChallenge() {
        var challenge = captcha.issue("local");
        AtomicInteger accepted = new AtomicInteger();
        Runnable attempt = () -> {
            try {
                captcha.verify(challenge.captchaId(), ANSWER);
                accepted.incrementAndGet();
            } catch (BusinessException expected) {
                assertThat(expected.getMessage()).contains("验证码错误或已过期");
            }
        };
        CompletableFuture.allOf(CompletableFuture.runAsync(attempt), CompletableFuture.runAsync(attempt)).join();
        assertThat(accepted).hasValue(1);
    }

    @Test
    void rateLimitRejectsBeforeGeneratingAnImageOrAllocatingChallengeStorage() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString())).thenReturn(0L);
        assertThatThrownBy(() -> captcha.issue("local")).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(429));
        verifyNoInteractions(generator);
        assertThat(fixture.stored).isEmpty();
    }

    @Test
    void unavailableRedisRejectsIssuanceAndVerificationWithoutAFallback() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString())).thenThrow(new RedisConnectionFailureException("test outage"));
        assertThatThrownBy(() -> captcha.issue("local")).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
        doThrow(new RedisConnectionFailureException("test outage")).when(redis)
                .execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList());
        assertThatThrownBy(() -> captcha.verify("a".repeat(32), ANSWER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(generator);
    }

    @Test
    void credentialsAreNotCheckedBeforeCaptchaAndPasswordFailureAlsoConsumesIt() {
        UserMapper users = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        RefreshTokenMapper tokens = mock(RefreshTokenMapper.class);
        AuthService auth = new AuthService(users, tokens, encoder, jwt, captcha);
        var challenge = captcha.issue("local");
        assertThatThrownBy(() -> auth.login(new AuthDtos.LoginRequest("user", "password",
                challenge.captchaId(), "WRONG"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users, encoder, jwt, tokens);
        var next = captcha.issue("local");
        when(users.selectOne(any())).thenReturn(User.registered("user", "encoded-password", "测试用户"));
        assertThatThrownBy(() -> auth.login(new AuthDtos.LoginRequest("user", "password", next.captchaId(), ANSWER)))
                .isInstanceOf(BusinessException.class).hasMessage("用户名或密码错误");
        verify(encoder).matches("password", "encoded-password");
        assertThat(fixture.stored).isEmpty();
        assertThatThrownBy(() -> captcha.verify(next.captchaId(), ANSWER)).isInstanceOf(BusinessException.class);
    }
}
