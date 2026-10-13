package com.opsagent.knowledge;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

class KnowledgePublicSearchRouteTest {
    @Test
    void publicAndInternalRoutesDelegateToTheSameDocumentScopedAuthorizationService()
            throws Exception {
        var service = mock(KnowledgeService.class);
        when(service.search("本次文档", 3, 7L, null))
                .thenReturn(List.of(Map.of("documentId", 7, "chunkId", 31)));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service)).build();
        for (String path : List.of("/api/knowledge/search", "/api/knowledge/internal/search")) {
            mvc.perform(
                            get(path)
                                    .param("query", "本次文档")
                                    .param("topK", "3")
                                    .param("documentId", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].documentId").value(7));
        }
        verify(service, times(2)).search("本次文档", 3, 7L, null);
    }
}
