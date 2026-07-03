package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for SpringAiMcpGitNexusClient. Mocks McpSyncClient so no
 * Spring AI auto-config or live MCP transport is required.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiMcpGitNexusClientTest {

    @Mock
    McpSyncClient mcpClient;

    @Test
    void queryMapsMcpTextToQueryResult() {
        // MCP query returns definitions[] (flat symbol list with "id") + processes[] (with heuristicLabel).
        String json = """
            {
              "processes": [
                {"heuristicLabel":"UserAuth"}
              ],
              "process_symbols": [],
              "definitions": [
                {"id":"Class:p:UserService","name":"UserService","filePath":"src/UserService.java","kind":"Class","startLine":1,"endLine":100,"module":"auth"}
              ]
            }
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        QueryResult result = client.query("UserService", "repo");

        assertEquals(1, result.symbols().size());
        assertEquals("UserService", result.symbols().get(0).name());
        assertEquals("src/UserService.java", result.symbols().get(0).filePath());
        assertEquals("Class:p:UserService", result.symbols().get(0).uid());
        assertEquals(1, result.processNames().size());
        assertTrue(result.processNames().contains("UserAuth"));
    }

    @Test
    void contextMapsMcpTextToSymbolContext() {
        // MCP context nests symbol under root.symbol (with content field for source code),
        // calls under root.incoming.calls, methods under root.outgoing.has_method.
        String json = """
            {
              "status":"found",
              "symbol":{
                "uid":"uid1","name":"UserService","kind":"Class",
                "filePath":"src/UserService.java","startLine":10,"endLine":100,
                "content":"public class UserService {}"
              },
              "incoming":{
                "calls":[{"uid":"Class:p:Ctrl","name":"Ctrl","filePath":"src/Ctrl.java","startLine":1,"endLine":50}],
                "imports":[]
              },
              "outgoing":{
                "has_method":[]
              },
              "processes":[]
            }
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        SymbolContext ctx = client.context("UserService", "repo");

        assertEquals("UserService", ctx.name());
        assertEquals("src/UserService.java", ctx.filePath());
        assertEquals(10, ctx.startLine());
        assertEquals("public class UserService {}", ctx.sourceContent());
        assertEquals(1, ctx.incomingCalls().size());
        assertEquals("Ctrl", ctx.incomingCalls().get(0).name());
    }

    @Test
    void impactMapsByDepthToMap() {
        // MCP impact returns target as object {id,name,type,filePath}, byDepth items use "id" not "uid".
        String json = """
            {
              "target":{"id":"Class:p:UserService","name":"UserService","type":"Class","filePath":"src/UserService.java"},
              "direction":"upstream",
              "impactedCount":1,
              "risk":"LOW",
              "summary":{},
              "affected_processes":[],
              "affected_modules":[],
              "byDepth":{"1":[{"depth":1,"id":"Class:p:Ctrl","name":"Ctrl","filePath":"src/Ctrl.java","startLine":1,"endLine":50,"relationType":"IMPORTS","confidence":1.0}]}
            }
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        ImpactResult result = client.impact("UserService", "upstream", "repo");

        assertEquals("UserService", result.target());
        assertEquals("LOW", result.risk());
        assertEquals(1, result.directDependents().size());
        assertEquals("Ctrl", result.directDependents().get(0).name());
    }

    @Test
    void detectChangesReturnsTrueWhenChangedSymbolsNonEmpty() {
        // MCP detect_changes returns changed_symbols (snake_case), not changedSymbols.
        String json = """
            {"summary":{},"changed_symbols":[{"id":"Class:p:UserService","name":"UserService"}],"affected_processes":[]}
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertTrue(client.detectChanges("repo"));
    }

    @Test
    void detectChangesReturnsFalseWhenChangedSymbolsEmpty() {
        String json = """
            {"summary":{},"changed_symbols":[],"affected_processes":[]}
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertFalse(client.detectChanges("repo"));
    }

    @Test
    void listReposParsesRepoArray() {
        // list_repos returns a bare JSON array of {name, path, ...} objects.
        String json = """
            [
              {"name":"mysql-binlog-connector-java","path":"/repos/mysql-binlog-connector-java","indexedDate":"2024-01-01"},
              {"name":"ai-factory","path":"/repos/ai-factory","indexedDate":"2024-06-01"}
            ]
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        List<RepoInfo> repos = client.listRepos();

        assertEquals(2, repos.size());
        assertEquals("mysql-binlog-connector-java", repos.get(0).name());
        assertEquals("/repos/mysql-binlog-connector-java", repos.get(0).path());
        assertEquals("ai-factory", repos.get(1).name());
    }

    @Test
    void throwsGitNexusExceptionWhenMcpReturnsError() {
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithError("tool failed"));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertThrows(GitNexusException.class, () -> client.query("X", "repo"));
    }

    @Test
    void throwsGitNexusExceptionOnTransportFailure() {
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenThrow(new RuntimeException("connection refused"));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertThrows(GitNexusException.class, () -> client.query("X", "repo"));
    }

    // --- helpers ---

    private CallToolResult callToolResultWithText(String json) {
        return new CallToolResult(List.of(new TextContent(json)), false);
    }

    private CallToolResult callToolResultWithError(String msg) {
        return new CallToolResult(List.of(new TextContent(msg)), true);
    }
}
