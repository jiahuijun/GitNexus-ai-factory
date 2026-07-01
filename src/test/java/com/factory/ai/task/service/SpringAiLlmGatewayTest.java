package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringAiLlmGatewayTest {

    @Mock ChatClient.Builder chatClientBuilder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec promptSpec;
    @Mock ChatClient.CallResponseSpec callResponseSpec;

    @Test
    void splitsTasksReturnsDrafts() {
        // 准备 mock 链:builder.build() → chatClient;chatClient.prompt() → promptSpec;
        // promptSpec.system(any).user(any) → promptSpec;.call() → callResponseSpec;
        // callResponseSpec.entity(any(ParameterizedTypeReference)) → List<TaskDraft>
        var drafts = List.of(
            new LlmGateway.TaskDraft("加getVipLevel", "UserService", "在 UserService 加 getVipLevel"),
            new LlmGateway.TaskDraft("加HTTP接口", "UserController", "在 Controller 加接口")
        );

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(any(ParameterizedTypeReference.class)))
            .thenReturn(drafts);

        var promptBuilder = new LlmPromptBuilder();
        var gateway = new SpringAiLlmGateway(chatClientBuilder, promptBuilder);

        var queryResult = new QueryResult(
            List.of(new SymbolRef("u1", "UserService", "src/UserService.java", 1, 100)),
            List.of("UserAuth")
        );

        var result = gateway.splitTasks("增加VIP等级查询", queryResult);

        assertEquals(2, result.size());
        assertEquals("加getVipLevel", result.get(0).stepName());
        assertEquals("UserService", result.get(0).targetSymbol());
    }

    @Test
    void wrapsExceptionsAsLlmException() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(any(ParameterizedTypeReference.class)))
            .thenThrow(new RuntimeException("LLM 500"));

        var promptBuilder = new LlmPromptBuilder();
        var gateway = new SpringAiLlmGateway(chatClientBuilder, promptBuilder);

        var queryResult = new QueryResult(List.of(), List.of());

        assertThrows(LlmException.class, () -> gateway.splitTasks("req", queryResult));
    }
}
