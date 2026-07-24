package com.example.opsagent.ai.client;

import org.springframework.stereotype.Component;

@Component
public class MockAiModelClient implements AiModelClient {

    @Override
    public String chat(String prompt) {
        return "mock answer";
    }
}
