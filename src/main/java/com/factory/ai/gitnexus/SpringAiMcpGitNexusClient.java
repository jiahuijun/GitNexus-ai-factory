package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI 1.0 MCP 客户端 SDK 的 {@link GitNexusClient} 真实实现。
 *
 * <p>通过 MCP（Model Context Protocol）HTTP 通道调用 GitNexus 知识图谱服务，
 * 将 {@code callTool} JSON-RPC 响应映射为既有 Java DTO（{@link QueryResult}、
 * {@link SymbolContext}、{@link ImpactResult}）。
 *
 * <p>仅当 {@code factory.clients.real.enabled=true} 时激活，确保测试 profile
 * （关闭了 Spring AI MCP 自动配置）不受影响，从而做到“真实客户端”与“测试桩”
 * 在同一代码库中隔离共存。
 *
 * <p>核心职责：
 * <ol>
 *   <li>封装 MCP {@code callTool} 调用与错误处理（{@link #callTool}）</li>
 *   <li>从 MCP 文本响应中解析 JSON 并映射到强类型 DTO</li>
 *   <li>对缺失/可选字段做安全回退，避免 NPE 阻断 AI 编排链路</li>
 * </ol>
 *
 * @see GitNexusClient
 */
@Service
@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")
public class SpringAiMcpGitNexusClient implements GitNexusClient {

    /** MCP 同步客户端，由 Spring AI 自动配置注入；封装了与 GitNexus 服务的 JSON-RPC 通信。 */
    private final McpSyncClient mcpClient;

    /** Jackson 序列化器，用于将 MCP 返回的文本 JSON 解析为 JsonNode 树以便手动映射。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造真实客户端。
     *
     * @param mcpClient 由 Spring AI 注入的 MCP 同步客户端，不可为 {@code null}
     */
    public SpringAiMcpGitNexusClient(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现细节：MCP {@code query} 工具返回 {@code definitions[]}（扁平符号列表）、
     * {@code process_symbols[]}（按执行流分组的符号）和 {@code processes[]}（含
     * {@code heuristicLabel}）。本方法从 {@code definitions[]} 解析所有符号，
     * 从 {@code processes[]} 收集执行流名称，供上层 AI 按链路主题做二次检索。
     */
    @Override
    public QueryResult query(String query, String repo) {
        // 固定 limit=5 / max_symbols=10：在召回率与上下文 token 成本之间折中，
        // 避免 AI 编排链路被过大的知识图谱片段撑爆。
        JsonNode root = callTool("query", Map.of(
            "query", query, "repo", repo, "limit", 5, "max_symbols", 10
        ));
        // MCP query 返回 definitions[]（扁平符号列表）+ process_symbols[]（按执行流分组）+ processes[]（含 heuristicLabel）。
        // definitions[] 是权威符号集合，processes[].heuristicLabel 提供执行流名称。
        List<SymbolRef> symbols = new ArrayList<>();
        List<String> processNames = new ArrayList<>();
        // 从 definitions[] 解析所有符号（使用 id 字段，非 uid）。
        for (JsonNode sym : root.path("definitions")) {
            symbols.add(parseSymbolRef(sym));
        }
        // 从 processes[] 收集执行流语义标签。
        for (JsonNode proc : root.path("processes")) {
            String label = proc.path("heuristicLabel").asText("");
            if (!label.isEmpty()) {
                processNames.add(label);
            }
        }
        return new QueryResult(symbols, processNames);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现细节：MCP {@code context} 工具返回单符号的完整上下文，符号本体嵌套在
     * {@code root.symbol} 下，调用引用按方向分组在 {@code root.incoming.calls} 和
     * {@code root.outgoing.has_method} 中。本方法逐字段映射，对缺失字段做安全回退
     * （如 name 缺失时回退为调用方传入的 symbolName，避免空名）。
     */
    @Override
    public SymbolContext context(String symbolName, String repo) {
        // include_content=true：请求返回源码内容，支撑 AI 阅读具体实现做深度推理。
        JsonNode root = callTool("context", Map.of(
            "name", symbolName, "repo", repo, "include_content", true
        ));
        // MCP context 将符号本体嵌套在 root.symbol 下，调用引用按方向分组在 root.incoming/outgoing 下。
        JsonNode sym = root.path("symbol");
        return new SymbolContext(
            sym.path("uid").asText(""),
            // name 字段缺失时回退为传入 symbolName：保证 DTO 至少有可读标识，
            // 即便服务端消歧失败也能让上层日志可追踪。
            sym.path("name").asText(symbolName),
            sym.path("kind").asText(""),
            sym.path("filePath").asText(""),
            optInt(sym, "startLine"),
            optInt(sym, "endLine"),
            root.path("sourceContent").asText(""),
            // incoming.calls 是直接调用此符号的引用列表。
            parseSymbolRefList(root.path("incoming").path("calls")),
            // outgoing.has_method 是此符号持有的方法（类→方法关系）。
            parseSymbolRefList(root.path("outgoing").path("has_method"))
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现细节：MCP {@code impact} 工具返回的 {@code byDepth} 是以深度字符串
     * （"1"/"2"/"3"）为键的 JSON 对象，本方法将其转为 {@code Map<Integer,...>}，
     * 便于上层按深度数值取用。{@code target} 字段在 MCP 响应中是对象
     * （含 {@code id/name/type/filePath}），本方法取其 {@code name} 作为标识。
     */
    @Override
    public ImpactResult impact(String target, String direction, String repo) {
        // maxDepth=3 / minConfidence=0.7：限制递归深度与置信度下限，
        // 过滤掉模糊匹配导致的噪声依赖，避免影响面爆炸式膨胀。
        JsonNode root = callTool("impact", Map.of(
            "target", target, "direction", direction, "repo", repo,
            "maxDepth", 3, "minConfidence", 0.7
        ));
        Map<Integer, List<SymbolRef>> byDepth = new HashMap<>();
        JsonNode bd = root.path("byDepth");
        // byDepth 是对象而非数组：键为深度的字符串形式（"1","2","3"），需手动 parseInt。
        bd.fields().forEachRemaining(e -> {
            int depth = Integer.parseInt(e.getKey());
            byDepth.put(depth, parseSymbolRefList(e.getValue()));
        });
        // MCP impact 的 target 是对象 {id,name,type,filePath} 而非纯字符串；
        // 取其 name 字段作为 target 标识，缺失时回退为传入的 target 参数。
        JsonNode targetNode = root.path("target");
        String targetName = targetNode.isObject()
            ? targetNode.path("name").asText(target)
            : targetNode.asText(target);
        return new ImpactResult(
            targetName,
            root.path("direction").asText(direction),
            root.path("risk").asText("UNKNOWN"),
            byDepth
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现细节：仅判断 {@code changedSymbols} 是否为非空数组，
     * 不返回详细变更列表——上层 AI 只需据此决定是否触发影响面重算。
     */
    @Override
    public boolean detectChanges(String repo) {
        // scope=unstaged：只关注工作区未暂存改动，对应"提交前自检"场景。
        JsonNode root = callTool("detect_changes", Map.of("repo", repo, "scope", "unstaged"));
        // MCP 返回 changed_symbols（snake_case），非 changedSymbols。
        JsonNode changed = root.path("changed_symbols");
        // isArray() 双重判断：既排除 null/missing，又排除非数组结构（如对象）。
        return changed.isArray() && !changed.isEmpty();
    }

    // --- internals ---

    /**
     * 统一的 MCP {@code callTool} 调用与响应解析入口。
     *
     * <p>封装三步：① 发起 JSON-RPC 调用；② 检测服务端错误标志；
     * ③ 将文本响应解析为 {@link JsonNode}。任何异常均包装为
     * {@link GitNexusException} 抛出，保证“快速失败”语义不被吞掉。
     *
     * @param toolName MCP 工具名（如 "query"、"context"）
     * @param args     传给工具的参数映射
     * @return 解析后的 JSON 树根节点
     * @throws GitNexusException 当服务端返回 error 标志、JSON 解析失败或底层 IO 异常时抛出
     */
    private JsonNode callTool(String toolName, Map<String, Object> args) {
        try {
            CallToolResult result = mcpClient.callTool(new CallToolRequest(toolName, args));
            // MCP 协议层：result.isError() 为 true 表示服务端业务错误（如工具不存在、参数非法），
            // 此时 content 中通常含错误说明文本；需要立即抛出而非继续解析。
            if (result.isError() != null && result.isError()) {
                throw new GitNexusException("MCP tool '" + toolName + "' returned error: " + extractText(result));
            }
            String text = extractText(result);
            // 将 MCP 文本内容解析为 JSON 树；后续方法用 path() 做安全导航避免 NPE。
            return mapper.readTree(text);
        } catch (GitNexusException e) {
            // 已经是 GitNexusException 则原样上抛，避免双重包装丢失原始信息。
            throw e;
        } catch (Exception e) {
            // 包装 IO/JSON 解析等非预期异常，保留 cause 以便排查。
            throw new GitNexusException("MCP tool '" + toolName + "' call failed", e);
        }
    }

    /**
     * 从 MCP {@code callTool} 结果中抽取拼接的纯文本内容。
     *
     * <p>MCP 响应的 {@code content} 是多模态列表（可含 TextContent、ImageContent 等），
     * GitNexus 仅返回 TextContent，本方法将其文本片段按顺序拼接为单一字符串，
     * 供后续 JSON 解析使用。
     *
     * @param result MCP 调用结果
     * @return 拼接后的文本；无文本内容时返回空字符串
     */
    private String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            // 使用模式匹配 instanceof 提取 TextContent，忽略其他类型内容。
            if (c instanceof TextContent tc) {
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    /**
     * 将单个 JSON 节点映射为 {@link SymbolRef}。
     *
     * <p>对所有字段使用 {@code path().asText("")} / {@link #optInt} 做安全导航，
     * 即便服务端漏返字段也只会得到空串/null 而非 NPE。符号唯一标识兼容两种字段名：
     * {@code uid}（context 响应）和 {@code id}（query/impact 响应）。
     *
     * @param node 符号的 JSON 节点
     * @return 填充好的 SymbolRef
     */
    private SymbolRef parseSymbolRef(JsonNode node) {
        // query/impact 响应使用 "id" 作为符号唯一标识；context 响应使用 "uid"。
        // 优先读 uid，缺失则读 id，两者皆无则回退空串，兼容两种 MCP 响应格式。
        String uid = node.path("uid").asText("");
        if (uid.isEmpty()) {
            uid = node.path("id").asText("");
        }
        return new SymbolRef(
            uid,
            node.path("name").asText(""),
            node.path("filePath").asText(""),
            optInt(node, "startLine"),
            optInt(node, "endLine")
        );
    }

    /**
     * 将 JSON 数组节点映射为 {@link SymbolRef} 列表。
     *
     * <p>对非数组输入（missing/null/对象）返回不可变空列表，避免后续 NPE，
     * 同时让调用方无需额外判空。
     *
     * @param arr JSON 数组节点；可能为 missing/null
     * @return 符号引用列表；输入非数组时返回 {@link List#of()}
     */
    private List<SymbolRef> parseSymbolRefList(JsonNode arr) {
        if (!arr.isArray()) {
            return List.of();
        }
        List<SymbolRef> list = new ArrayList<>();
        for (JsonNode n : arr) {
            list.add(parseSymbolRef(n));
        }
        return list;
    }

    /**
     * 安全读取可选整数字段。
     *
     * <p>区别于 {@code asInt(0)} 的“默认 0”语义——行号 0 可能被误判为有效行，
     * 故缺失/null 时返回 {@code null}，让 DTO 字段以 Integer 包装类型表达“未知”。
     *
     * @param node  JSON 节点
     * @param field 字段名
     * @return 字段值；缺失或为 null 时返回 {@code null}
     */
    private Integer optInt(JsonNode node, String field) {
        JsonNode n = node.path(field);
        // MissingNode vs null 节点都视为缺失，统一返回 null 而非 0。
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }
}
