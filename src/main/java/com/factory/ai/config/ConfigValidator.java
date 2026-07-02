package com.factory.ai.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时配置校验：检测 LLM 和 GitNexus 的关键配置是否为占位符。
 *
 * <p>如果用户 clone 项目后忘记设置环境变量，application.yml 中的默认值是
 * {@code your-base-url} / {@code your-api-key} / {@code your-model}。
 * 如果不校验，应用能正常启动，但第一次调用 LLM 时才会报错，且错误信息含糊。</p>
 *
 * <p>本类在 {@code @PostConstruct} 阶段检测这些值是否仍为占位符，
 * 如果是则抛出异常，应用启动失败并打印清晰的提示信息。</p>
 */
@Configuration
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    @Value("${spring.ai.openai.base-url:}")
    private String llmBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String llmApiKey;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String llmModel;

    @Value("${gitnexus.mcp.url:}")
    private String gitnexusUrl;

    @Value("${factory.clients.real.enabled:true}")
    private boolean realClientsEnabled;

    @PostConstruct
    public void validate() {
        if (!realClientsEnabled) {
            log.info("factory.clients.real.enabled=false, skipping config validation (test mode)");
            return;
        }

        StringBuilder errors = new StringBuilder();

        if (isPlaceholder(llmBaseUrl)) {
            errors.append("\n  - LLM_BASE_URL is not set (current value: \"").append(llmBaseUrl)
                .append("\"). Set it to your OpenAI-compatible API base URL.");
        }
        if (isPlaceholder(llmApiKey)) {
            errors.append("\n  - LLM_API_KEY is not set (current value: \"").append(llmApiKey)
                .append("\"). Set it to your API key.");
        }
        if (isPlaceholder(llmModel)) {
            errors.append("\n  - LLM_MODEL is not set (current value: \"").append(llmModel)
                .append("\"). Set it to the model name (e.g., qwen-plus).");
        }

        if (errors.length() > 0) {
            throw new IllegalStateException(
                "\n\n========================================\n" +
                "AI Factory 启动失败：缺少必要配置！\n" +
                "请设置以下环境变量后重试：\n" +
                errors.toString() +
                "\n\n示例 (bash):\n" +
                "  export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1\n" +
                "  export LLM_API_KEY=your-api-key\n" +
                "  export LLM_MODEL=qwen-plus\n" +
                "\n详见 README.md 的 Quick Start 章节。\n" +
                "========================================\n"
            );
        }

        log.info("Config validation passed: LLM base-url={}, model={}, GitNexus url={}",
            llmBaseUrl, llmModel, gitnexusUrl);
    }

    /**
     * 判断值是否为占位符或空。
     */
    private boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) return true;
        return value.startsWith("your-") || value.equals("placeholder");
    }
}
