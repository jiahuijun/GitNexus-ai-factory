package com.factory.ai;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FactoryApplicationTest {
    @MockBean
    GitNexusClient gitNexusClient;
    @MockBean
    LlmGateway llmGateway;

    @Test
    void contextLoads() {
    }
}
