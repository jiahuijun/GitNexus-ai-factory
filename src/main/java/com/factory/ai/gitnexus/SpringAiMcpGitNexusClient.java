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
 * Real {@link GitNexusClient} implementation that calls GitNexus via the
 * Model Context Protocol (MCP) over HTTP using Spring AI 1.0's MCP client SDK.
 *
 * <p>Maps MCP {@code callTool} JSON-RPC responses to the existing Java DTOs
 * ({@link QueryResult}, {@link SymbolContext}, {@link ImpactResult}). Only
 * activated when {@code factory.clients.real.enabled=true} so the test
 * profile (which disables Spring AI MCP auto-config) is unaffected.
 */
@Service
@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")
public class SpringAiMcpGitNexusClient implements GitNexusClient {

    private final McpSyncClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpringAiMcpGitNexusClient(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @Override
    public QueryResult query(String query, String repo) {
        JsonNode root = callTool("query", Map.of(
            "query", query, "repo", repo, "limit", 5, "max_symbols", 10
        ));
        // MCP query returns processes grouped; flatten into symbols + process names.
        List<SymbolRef> symbols = new ArrayList<>();
        List<String> processNames = new ArrayList<>();
        for (JsonNode proc : root.path("processes")) {
            String label = proc.path("heuristicLabel").asText("");
            if (!label.isEmpty()) {
                processNames.add(label);
            }
            for (JsonNode sym : proc.path("symbols")) {
                symbols.add(parseSymbolRef(sym));
            }
        }
        return new QueryResult(symbols, processNames);
    }

    @Override
    public SymbolContext context(String symbolName, String repo) {
        JsonNode root = callTool("context", Map.of(
            "name", symbolName, "repo", repo, "include_content", true
        ));
        return new SymbolContext(
            root.path("uid").asText(""),
            root.path("name").asText(symbolName),
            root.path("kind").asText(""),
            root.path("filePath").asText(""),
            optInt(root, "startLine"),
            optInt(root, "endLine"),
            root.path("sourceContent").asText(""),
            parseSymbolRefList(root.path("incomingCalls")),
            parseSymbolRefList(root.path("outgoingMethods"))
        );
    }

    @Override
    public ImpactResult impact(String target, String direction, String repo) {
        JsonNode root = callTool("impact", Map.of(
            "target", target, "direction", direction, "repo", repo,
            "maxDepth", 3, "minConfidence", 0.7
        ));
        Map<Integer, List<SymbolRef>> byDepth = new HashMap<>();
        JsonNode bd = root.path("byDepth");
        bd.fields().forEachRemaining(e -> {
            int depth = Integer.parseInt(e.getKey());
            byDepth.put(depth, parseSymbolRefList(e.getValue()));
        });
        return new ImpactResult(
            root.path("target").asText(target),
            root.path("direction").asText(direction),
            root.path("risk").asText("UNKNOWN"),
            byDepth
        );
    }

    @Override
    public boolean detectChanges(String repo) {
        JsonNode root = callTool("detect_changes", Map.of("repo", repo, "scope", "unstaged"));
        JsonNode changed = root.path("changedSymbols");
        return changed.isArray() && !changed.isEmpty();
    }

    // --- internals ---

    private JsonNode callTool(String toolName, Map<String, Object> args) {
        try {
            CallToolResult result = mcpClient.callTool(new CallToolRequest(toolName, args));
            if (result.isError() != null && result.isError()) {
                throw new GitNexusException("MCP tool '" + toolName + "' returned error: " + extractText(result));
            }
            String text = extractText(result);
            return mapper.readTree(text);
        } catch (GitNexusException e) {
            throw e;
        } catch (Exception e) {
            throw new GitNexusException("MCP tool '" + toolName + "' call failed", e);
        }
    }

    private String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc) {
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private SymbolRef parseSymbolRef(JsonNode node) {
        return new SymbolRef(
            node.path("uid").asText(""),
            node.path("name").asText(""),
            node.path("filePath").asText(""),
            optInt(node, "startLine"),
            optInt(node, "endLine")
        );
    }

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

    private Integer optInt(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }
}
