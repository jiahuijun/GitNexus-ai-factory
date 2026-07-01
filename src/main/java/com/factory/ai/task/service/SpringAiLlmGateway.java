package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 Spring AI {@link ChatClient} 的 {@link LlmGateway} 实现。
 *
 * <p>在拆解流水线中负责调用大模型（如 OpenAI / 阿里通义等，由 Spring AI 配置决定），
 * 使用 {@link LlmPromptBuilder} 组装提示词，将 LLM 输出的 JSON 数组反序列化为
 * {@code List<TaskDraft>}。</p>
 *
 * <p>通过 {@code @ConditionalOnProperty(name="factory.clients.real.enabled", havingValue="true")}
 * 进行门控：仅在生产/真实客户端配置启用时才装配此 Bean，
 * 测试环境下使用 stub 实现以避免真实 LLM 调用。</p>
 *
 * <p>"不降级"原则：任何底层异常（网络、超时、反序列化失败等）均被包装为
 * {@link LlmException} 抛出，触发上游 {@code @Transactional} 回滚。</p>
 *
 * @see LlmGateway
 * @see LlmPromptBuilder
 */
@Service
@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;
    private final LlmPromptBuilder promptBuilder;

    /**
     * 构造网关，使用 Spring AI 注入的 {@code ChatClient.Builder} 构建 ChatClient 实例。
     *
     * @param chatClientBuilder Spring AI 自动配置提供的 ChatClient.Builder，封装了底层模型客户端
     * @param promptBuilder     提示词构建器，负责组装系统提示词与用户消息
     */
    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder, LlmPromptBuilder promptBuilder) {
        // 由 Builder 构建不可变的 ChatClient，绑定具体的模型配置
        this.chatClient = chatClientBuilder.build();
        this.promptBuilder = promptBuilder;
    }

    /**
     * 调用 LLM 拆解需求为任务草稿列表。
     *
     * <p>使用 Spring AI ChatClient 的 fluent API 链式调用：
     * <ol>
     *   <li>{@code .system()} 设置静态系统提示词（拆解规则）</li>
     *   <li>{@code .user()} 设置动态用户消息（需求 + 摸底结果）</li>
     *   <li>{@code .call()} 发起同步请求</li>
     *   <li>{@code .entity()} 将返回的 JSON 数组反序列化为 {@code List<TaskDraft>}</li>
     * </ol>
     * 任何环节失败均包装为 {@link LlmException} 抛出。</p>
     *
     * @param requirement 管理员提交的产品需求文本
     * @param context     GitNexus query() 返回的摸底结果，约束 LLM 只能从中选取 targetSymbol
     * @return LLM 输出的任务草稿列表；空列表表示无需拆解
     * @throws LlmException 当 LLM 调用、网络、反序列化失败时抛出（含原始 cause）
     */
    @Override
    public List<TaskDraft> splitTasks(String requirement, QueryResult context) {
        try {
            // fluent API 链：系统提示词 → 用户消息 → 同步调用 → 反序列化为 List<TaskDraft>
            return chatClient.prompt()
                .system(LlmPromptBuilder.SYSTEM_PROMPT)
                .user(promptBuilder.buildUserMessage(requirement, context))
                .call()
                .entity(new ParameterizedTypeReference<List<TaskDraft>>() {});
        } catch (Exception e) {
            // 包装为非受检异常，触发上游事务回滚，符合"不降级"原则
            throw new LlmException("LLM splitTasks failed for requirement: " + requirement, e);
        }
    }
}
