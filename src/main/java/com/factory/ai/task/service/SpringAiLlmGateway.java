package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final LlmPromptBuilder promptBuilder;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder, LlmPromptBuilder promptBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.promptBuilder = promptBuilder;
    }

    @Override
    public List<TaskDraft> splitTasks(String requirement, QueryResult context) {
        try {
            return chatClient.prompt()
                .system(LlmPromptBuilder.SYSTEM_PROMPT)
                .user(promptBuilder.buildUserMessage(requirement, context))
                .call()
                .entity(new ParameterizedTypeReference<List<TaskDraft>>() {});
        } catch (Exception e) {
            throw new LlmException("LLM splitTasks failed for requirement: " + requirement, e);
        }
    }
}
