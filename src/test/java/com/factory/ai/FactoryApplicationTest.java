package com.factory.ai;

import com.factory.ai.gitnexus.GitNexusClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FactoryApplicationTest {
    @MockBean
    GitNexusClient gitNexusClient;

    @Test
    void contextLoads() {
    }
}
