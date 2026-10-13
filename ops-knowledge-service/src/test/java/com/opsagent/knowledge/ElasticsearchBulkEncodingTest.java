package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从实际 HTTP 请求验证 Bulk NDJSON 的 UTF-8 编码与行边界，防止中文被替换为问号。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ElasticsearchBulkEncodingTest {
    @Test
    void shouldSendChineseAndMultilineContentAsExactUtf8NdjsonBytes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"items\":[{\"index\":{\"status\":201}},{\"index\":{\"status\":200}}]}"));
            server.start();
            ObjectMapper mapper = new ObjectMapper();
            VectorProperties properties = new VectorProperties();
            properties.setElasticsearchUrl(server.url("/").toString());
            ElasticsearchVectorStore store = new ElasticsearchVectorStore(properties, mapper);
            Map<String, Object> first = Map.of(
                    "chunkId", 1L, "documentName", "服务清单.md",
                    "headingPath", List.of("业务系统", "服务依赖"),
                    "content", "订单服务依赖库存服务。\n第二行：检查 Redis 连接。\r\n符号：中文、é、🚀。\n");
            Map<String, Object> second = Map.of(
                    "chunkId", 2L, "documentName", "工单排查手册.md",
                    "content", "路径 C:\\运维\\记录，字段包含\"中文引号\"和制表符\t结束。");

            var result = store.bulkIndex("knowledge-test", List.of(
                    new ElasticsearchVectorStore.IndexDocument("1:1:0", 1L, first),
                    new ElasticsearchVectorStore.IndexDocument("1:1:1", 2L, second)));

            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/_bulk?refresh=wait_for");
            assertThat(MediaType.parseMediaType(request.getHeader("Content-Type")).getCharset())
                    .isEqualTo(StandardCharsets.UTF_8);
            byte[] bytes = request.getBody().readByteArray();
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            assertThat(body).contains("服务清单.md", "订单服务依赖库存服务", "é", "🚀").endsWith("\n");
            String[] lines = body.split("\n", -1);
            assertThat(lines).hasSize(5);
            assertThat(lines[4]).isEmpty();
            assertThat(mapper.readTree(lines[0]).path("index").path("_index").asText())
                    .isEqualTo("knowledge-test");
            assertThat(mapper.readTree(lines[1])).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(first)));
            assertThat(mapper.readTree(lines[2]).path("index").path("_id").asText()).isEqualTo("1:1:1");
            assertThat(mapper.readTree(lines[3])).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(second)));
            assertThat(result.succeededChunkIds()).containsExactly(1L, 2L);
            assertThat(result.failures()).isEmpty();
        }
    }
}
