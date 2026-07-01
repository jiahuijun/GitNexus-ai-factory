package com.factory.ai.gitnexus;

/**
 * GitNexus 客户端调用异常。
 *
 * <p>当通过 MCP 协议访问 GitNexus 知识图谱失败时抛出，包括但不限于：
 * <ul>
 *   <li>MCP {@code callTool} 返回错误响应（服务端业务错误）</li>
 *   <li>网络/协议层异常（连接失败、JSON 解析失败等）</li>
 *   <li>响应内容不符合预期结构</li>
 * </ul>
 *
 * <p>设计上不进行任何降级处理——知识图谱不可用时应当让调用方感知到失败，
 * 以避免 AI 在缺失代码上下文的情况下做出错误决策。继承自 {@link RuntimeException}
 * 为非受检异常，避免在 AI 编排链路上污染方法签名。
 */
public class GitNexusException extends RuntimeException {

    /**
     * 使用错误消息构造异常。
     *
     * @param message 错误描述，说明失败原因（例如哪个 MCP 工具失败）
     */
    public GitNexusException(String message) {
        super(message);
    }

    /**
     * 使用错误消息与底层原因构造异常。
     *
     * @param message 错误描述
     * @param cause   底层异常（如 IOException、JsonProcessingException），用于保留原始堆栈
     */
    public GitNexusException(String message, Throwable cause) {
        super(message, cause);
    }
}
