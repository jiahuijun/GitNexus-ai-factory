package com.factory.ai.task.web;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.task.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GitNexusClient gitNexusClient;
    @MockBean LlmGateway llmGateway;

    @Test
    void claimNonexistentReturns409() throws Exception {
        mvc.perform(post("/tasks/999999/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1}"))
            .andExpect(status().isConflict());
    }

    @Test
    void completeNonexistentReturnsOkFalse() throws Exception {
        mvc.perform(post("/tasks/999999/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":1,\"repo\":\"r\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }
}
