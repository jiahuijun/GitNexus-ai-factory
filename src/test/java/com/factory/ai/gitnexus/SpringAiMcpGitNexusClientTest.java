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
        // MCP query tool returns processes grouped; client flattens into symbols + process names.
        String json = """
            {
              "processes": [
                {"heuristicLabel":"UserAuth","symbols":[
                  {"uid":"Class:p:UserService","name":"UserService","filePath":"src/UserService.java","kind":"Class"}
                ]}
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
        assertEquals(1, result.processNames().size());
        assertTrue(result.processNames().contains("UserAuth"));
    }

    @Test
    void contextMapsMcpTextToSymbolContext() {
        String json = """
            {
              "uid":"uid1","name":"UserService","kind":"Class",
              "filePath":"src/UserService.java","startLine":10,"endLine":100,
              "sourceContent":"public class UserService {}",
              "incomingCalls":[{"uid":"Class:p:Ctrl","name":"Ctrl","filePath":"src/Ctrl.java","startLine":1,"endLine":50}],
              "outgoingMethods":[]
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
        String json = """
            {
              "target":"UserService","direction":"upstream","risk":"LOW",
              "byDepth":{"1":[{"uid":"Class:p:Ctrl","name":"Ctrl","filePath":"src/Ctrl.java","startLine":1,"endLine":50}]}
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
        String json = """
            {"changedSymbols":[{"uid":"Class:p:UserService","name":"UserService"}],"risk":"LOW"}
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertTrue(client.detectChanges("repo"));
    }

    @Test
    void detectChangesReturnsFalseWhenChangedSymbolsEmpty() {
        String json = """
            {"changedSymbols":[],"risk":"LOW"}
            """;
        when(mcpClient.callTool(any(CallToolRequest.class)))
            .thenReturn(callToolResultWithText(json));

        var client = new SpringAiMcpGitNexusClient(mcpClient);
        assertFalse(client.detectChanges("repo"));
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
