package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 官方网页读取只接受目录地址，正文有界且外部页面不能变成操作指令。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OfficialDocumentationClientTest {
    @Test
    void fixedOfficialPageProvidesRealExcerptAndReusesFreshCache() {
        var calls = new AtomicInteger();
        var client =
                new OfficialDocumentationClient(
                        uri -> {
                            calls.incrementAndGet();
                            assertThat(uri.toString())
                                    .isEqualTo("https://www.rabbitmq.com/docs/alarms");
                            return new OfficialDocumentationClient.Download(
                                    200,
                                    "text/html; charset=utf-8",
                                    ("<html><body><script>hidden script never"
                                         + " cite</script><p>RabbitMQ memory and disk alarms block"
                                         + " publishing connections until resource use returns"
                                         + " below its threshold.</p></body></html>")
                                            .getBytes(StandardCharsets.UTF_8));
                        },
                        Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
        var result = client.search("RABBITMQ_ALARMS");
        assertThat(result.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("querySentExternally").asBoolean()).isFalse();
        assertThat(result.path("citations").toString())
                .contains("memory and disk")
                .doesNotContain("hidden script");
        assertThat(client.search("RABBITMQ_ALARMS").path("cacheHit").asBoolean()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retrievesDetailedBodyAndKeepsShortReadCommandsWithoutNavigationOrTokenCorruption() {
        var client =
                new OfficialDocumentationClient(
                        uri -> {
                            assertThat(uri.toString())
                                    .isEqualTo(
                                            "https://kubernetes.io/docs/tasks/debug/"
                                                    + "debug-application/debug-service/");
                            return new OfficialDocumentationClient.Download(
                                    200,
                                    "text/html",
                                    """
<html><body><aside>kubectl get pods navigation decoy</aside>
<main><h2>Check Pods</h2><p>Read the current Pod status.</p>
<pre><span>kubectl</span> get pods</pre>
<p>Confirm the Service exists before diagnosing networking.</p>
<pre>kubectl get svc hostnames</pre>
<p>Check which ready Pods the Service actually selects.</p>
<pre>kubectl get endpointslices -l kubernetes.io/service-name=hostnames</pre>
<script>kubectl get pods malicious script</script></main>
<footer>kubectl get svc unrelated footer</footer></body></html>
"""
                                            .getBytes(StandardCharsets.UTF_8));
                        },
                        Clock.systemUTC());

        var result = client.search("KUBERNETES_TROUBLESHOOTING");

        assertThat(result.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("citations").toString())
                .contains(
                        "kubectl get pods",
                        "kubectl get svc hostnames",
                        "kubectl get endpointslices -l kubernetes.io/service-name=hostnames")
                .doesNotContain("navigation decoy", "malicious script", "unrelated footer");
        assertThat(result.path("querySentExternally").asBoolean()).isFalse();
    }

    @Test
    void keepsSyntaxHighlightedCommandLineBreaksAndYamlIndentation() throws Exception {
        var paragraphs =
                OfficialDocumentationClient.extract(
                        ("<main><pre><span class='line'>kubectl get pods \\\n"
                             + "</span><span class='line'>  -o wide</span></pre><pre>spec:\n"
                             + "  replicas: 3\n"
                             + "  selector:\n"
                             + "    app: demo</pre></main>")
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(paragraphs)
                .contains(
                        "kubectl get pods \\\n  -o wide",
                        "spec:\n  replicas: 3\n  selector:\n    app: demo");
    }

    @Test
    void excerptSelectionCoversDifferentChecksBeforeRepeatingTheSamePodTopic() {
        var passages =
                OfficialDocumentationClient.rank(
                        List.of(
                                "kubectl get pods ".repeat(50),
                                "Another kubectl get pods example ".repeat(30),
                                "kubectl get svc hostnames ".repeat(35),
                                "kubectl get endpointslices ".repeat(35)),
                        List.of(
                                "kubectl get pods",
                                "kubectl get svc",
                                "kubectl get endpointslices"));

        assertThat(passages).hasSize(3);
        assertThat(String.join("\n", passages))
                .contains("kubectl get pods", "kubectl get svc", "kubectl get endpointslices");
    }

    @Test
    void refusesPrivateAddressUnregisteredUrlAndOversizedResult() throws Exception {
        var uri = URI.create("https://www.rabbitmq.com/docs/alarms");
        assertThatThrownBy(
                        () ->
                                OfficialDocumentationClient.validateAddress(
                                        uri,
                                        new InetAddress[] {InetAddress.getByName("127.0.0.1")}))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(
                        () ->
                                OfficialDocumentationClient.validateAddress(
                                        URI.create("https://www.rabbitmq.com/other"),
                                        new InetAddress[] {InetAddress.getByName("1.1.1.1")}))
                .isInstanceOf(java.io.IOException.class);
        var client =
                new OfficialDocumentationClient(
                        ignored ->
                                new OfficialDocumentationClient.Download(
                                        200,
                                        "text/html",
                                        new byte[OfficialDocumentationClient.MAX_BYTES + 1]),
                        Clock.systemUTC());
        assertThat(client.search("RABBITMQ_ALARMS").path("reasonCode").asText())
                .isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void failedReadReturnsNoFakeCitationOrRawExceptionDetails() {
        var client =
                new OfficialDocumentationClient(
                        ignored -> {
                            throw new IllegalStateException("private transport detail");
                        },
                        Clock.systemUTC());
        var result = client.search("RABBITMQ_ALARMS");
        assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("citations").isEmpty()).isTrue();
        assertThat(result.toString()).doesNotContain("private transport detail");
    }
}
