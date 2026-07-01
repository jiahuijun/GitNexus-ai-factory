package com.factory.ai.gitnexus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * MCP Streamable HTTP 客户端传输层实现。
 *
 * <p>GitNexus MCP 服务端使用 <strong>Streamable HTTP</strong> 协议（MCP 2025-03-26）：
 * 客户端 POST JSON-RPC 请求到单一端点，服务端以 SSE（Server-Sent Events）格式返回响应。
 * 这与 MCP SDK 0.10.0 内置的 {@link io.modelcontextprotocol.client.transport.HttpClientSseClientTransport}
 * 不兼容——后者期望旧版 SSE 双端点协议（GET 打开流 + POST 到不同端点）。
 *
 * <p>本类实现 {@link McpClientTransport} 接口，将每个 JSON-RPC 消息以 POST 发送至
 * GitNexus 端点，解析 SSE 格式响应体，通过 connect() 时注入的 handler 回调将响应
 * 路由回 MCP 会话层。
 *
 * <p>典型用法：
 * <pre>{@code
 * var transport = new StreamableHttpClientTransport("http://localhost:4747/api/mcp", mapper);
 * var client = McpClient.sync(transport).build();
 * client.initialize();
 * var result = client.callTool(new CallToolRequest("query", Map.of(...)));
 * }</pre>
 *
 * @see McpClientTransport
 */
public class StreamableHttpClientTransport implements McpClientTransport {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpClientTransport.class);

    /** HTTP 客户端，复用连接池。 */
    private final HttpClient httpClient;

    /** GitNexus MCP 端点 URL（如 {@code http://localhost:4747/api/mcp}）。 */
    private final String endpoint;

    /** Jackson 序列化器，用于 JSON-RPC 消息的序列化与反序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * MCP 会话 ID，由 initialize 响应的 {@code Mcp-Session-Id} 头返回。
     * 后续所有请求必须携带此 ID，否则服务端返回 400 "Server not initialized"。
     */
    private volatile String sessionId;

    /**
     * connect() 时由 MCP 会话注入的消息处理器。
     * 每当从 SSE 响应中解析出 JSONRPCMessage 时，通过此回调将其路由回会话层，
     * 使等待响应的 sendRequest Future 得以完成。
     */
    @SuppressWarnings("rawtypes")
    private Function messageHandler;

    /**
     * 构造传输层。
     *
     * @param endpoint     GitNexus MCP 端点 URL
     * @param objectMapper Jackson 序列化器
     */
    public StreamableHttpClientTransport(String endpoint, ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * 连接到 MCP 服务端。
     *
     * <p>Streamable HTTP 协议无需建立持久连接——每次 sendMessage 都是独立的 HTTP 请求。
     * 本方法仅缓存会话注入的 handler，供后续 sendMessage 回调使用。
     *
     * @param handler MCP 会话注入的消息路由函数
     * @return Mono.empty()，无需异步等待连接建立
     */
    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        this.messageHandler = handler;
        return Mono.empty();
    }

    /**
     * 发送 JSON-RPC 消息并处理响应。
     *
     * <p>实现流程：
     * <ol>
     *   <li>将消息序列化为 JSON</li>
     *   <li>POST 到 GitNexus 端点，设置 {@code Accept: application/json, text/event-stream}</li>
     *   <li>解析 SSE 格式响应体（{@code event: message\ndata: {json}}）</li>
     *   <li>对每条解析出的响应消息调用 handler 回调，路由回 MCP 会话</li>
     * </ol>
     *
     * @param message JSON-RPC 请求消息
     * @return Mono.empty()（响应通过 handler 回调传递，而非返回值）
     */
    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> sendMessage(JSONRPCMessage message) {
        return Mono.fromCallable(() -> {
            String body = objectMapper.writeValueAsString(message);
            log.debug("MCP POST to {} body: {}", endpoint, body);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            // 携带上一次响应中获得的 Mcp-Session-Id
            if (sessionId != null) {
                reqBuilder.header("Mcp-Session-Id", sessionId);
            }
            HttpRequest request = reqBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            log.debug("MCP response status: {}, body: {}", statusCode, response.body());
            // 捕获 Mcp-Session-Id 响应头（initialize 响应首次返回，后续请求需携带）
            String sid = response.headers().firstValue("mcp-session-id").orElse(null);
            if (sid != null) {
                sessionId = sid;
                log.debug("MCP session ID captured: {}", sid);
            }
            // 通知类消息（如 notifications/initialized）可能返回 202 无响应体
            if (statusCode == 202 || response.body() == null || response.body().isBlank()) {
                return (Void) null;
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException(
                    "MCP HTTP request failed: " + statusCode + " - " + response.body());
            }

            // 解析 SSE 格式响应：每条事件以 \n\n 分隔，data: 行包含 JSON-RPC 消息体。
            List<JSONRPCMessage> messages = parseSseResponse(response.body());
            for (JSONRPCMessage msg : messages) {
                // 通过 handler 回调将响应路由回 MCP 会话层，
                // 使等待此响应的 sendRequest().block() 得以完成。
                @SuppressWarnings("unchecked")
                Mono<Void> processed = ((Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>) messageHandler)
                    .apply(Mono.just(msg)).then();
                processed.subscribe();
            }
            return (Void) null;
        }).then();
    }

    /**
     * 解析 SSE 格式响应体为 JSON-RPC 消息列表。
     *
     * <p>SSE 格式：
     * <pre>
     * event: message
     * data: {"jsonrpc":"2.0","result":{...},"id":1}
     *
     * event: message
     * data: {"jsonrpc":"2.0","result":{...},"id":2}
     * </pre>
     *
     * @param responseBody HTTP 响应体文本
     * @return 解析出的 JSON-RPC 消息列表；无有效消息时返回空列表
     */
    private List<JSONRPCMessage> parseSseResponse(String responseBody) {
        List<JSONRPCMessage> messages = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            return messages;
        }
        // SSE 事件以空行（\n\n）分隔
        String[] events = responseBody.split("\n\n");
        for (String event : events) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\n")) {
                if (line.startsWith("data: ")) {
                    data.append(line, 6, line.length());
                } else if (line.startsWith("data:")) {
                    data.append(line, 5, line.length());
                }
            }
            if (data.length() > 0) {
                try {
                    // 使用 McpSchema 提供的静态方法处理多态反序列化
                    JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper, data.toString());
                    messages.add(msg);
                } catch (Exception e) {
                    log.error("Failed to parse SSE data: {}", data, e);
                }
            }
        }
        return messages;
    }

    /**
     * 优雅关闭传输层。
     *
     * <p>Streamable HTTP 无持久连接，直接返回 Mono.empty()。
     *
     * @return Mono.empty()
     */
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    /**
     * 将对象反序列化为指定类型，委托给 Jackson ObjectMapper。
     *
     * @param data    原始数据对象
     * @param typeRef 目标类型引用
     * @return 反序列化后的对象
     * @param <T>     目标类型
     */
    @Override
    public <T> T unmarshalFrom(Object data, com.fasterxml.jackson.core.type.TypeReference<T> typeRef) {
        return objectMapper.convertValue(data, typeRef);
    }
}
