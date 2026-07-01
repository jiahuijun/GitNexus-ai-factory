package com.factory.ai.gitnexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为 GitNexus MCP 服务端配置 {@link McpSyncClient} Bean。
 *
 * <p>Spring AI 1.0 内置的 SSE 自动配置（{@code SseHttpClientTransportAutoConfiguration}）
 * 使用旧版 SSE 双端点协议，与 GitNexus 的 Streamable HTTP 协议不兼容。
 * 本配置类绕过 SSE 自动配置，直接通过 {@link StreamableHttpClientTransport}
 * 创建 {@link McpSyncClient}，适配 GitNexus 的 POST → SSE-response 模式。
 *
 * <p>仅当 {@code factory.clients.real.enabled=true} 时激活，与
 * {@link SpringAiMcpGitNexusClient} 的门控条件一致。
 *
 * @see StreamableHttpClientTransport
 * @see SpringAiMcpGitNexusClient
 */
@Configuration
@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")
@ConditionalOnClass(McpSyncClient.class)
public class GitNexusMcpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GitNexusMcpClientConfiguration.class);

    /**
     * 创建并初始化 MCP 同步客户端。
     *
     * <p>流程：
     * <ol>
     *   <li>从配置读取 GitNexus MCP 端点 URL（默认 {@code http://localhost:4747/api/mcp}）</li>
     *   <li>构造 {@link StreamableHttpClientTransport}</li>
     *   <li>通过 {@link McpClient#sync} 构建 {@link McpSyncClient}</li>
     *   <li>调用 {@link McpSyncClient#initialize()} 完成 MCP 握手</li>
     * </ol>
     *
     * @param objectMapper Jackson 序列化器，由 Spring Boot 自动注入
     * @return 已初始化的 MCP 同步客户端
     */
    @Bean
    public McpSyncClient mcpSyncClient(ObjectMapper objectMapper,
                                       @Value("${gitnexus.mcp.url:http://localhost:4747/api/mcp}") String endpoint) {
        log.info("Creating McpSyncClient with Streamable HTTP transport, endpoint: {}", endpoint);

        StreamableHttpClientTransport transport = new StreamableHttpClientTransport(endpoint, objectMapper);
        McpSyncClient client = McpClient.sync(transport)
            .requestTimeout(java.time.Duration.ofSeconds(30))
            .build();

        // 完成 MCP 握手：发送 initialize 请求，协商协议版本与能力。
        // 握手失败会抛异常，阻止 Spring 上下文启动——符合"不降级"原则。
        client.initialize();
        log.info("McpSyncClient initialized successfully, server: {}", client.getServerInfo());
        return client;
    }
}
