# 真实客户端接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 stub 的 `GitNexusClient` 和 `LlmGateway` 替换为 Spring AI 1.0 驱动的真实客户端实现,让 `POST /tasks/decompose` 端到端跑通真实 GitNexus + 本地 LLM 数据。

**Architecture:** Spring Boot 3.2.5 → 3.4.5 升级,引入 Spring AI 1.0 GA(`spring-ai-starter-mcp-client` + `spring-ai-starter-model-openai`)。`SpringAiMcpGitNexusClient` 通过 MCP-over-HTTP 调用 GitNexus 4 个工具;`SpringAiLlmGateway` 通过 OpenAI 兼容接口 + structured output 拆解任务。无降级:失败即抛,事务回滚。

**Tech Stack:** Java 17, Spring Boot 3.4.5, Spring AI 1.0.0, Spring Data JPA, MySQL 8, H2(test), JUnit 5, Mockito

## Global Constraints

- Java 17+, Spring Boot 3.4.5 (从 3.2.5 升级)
- Spring AI 1.0.0 GA(`spring-ai-bom` 1.0.0)
- 包名根:`com.factory.ai`
- 真实 client bean 用 `@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")` — 测试 profile 关闭,只走 fake
- 无降级:GitNexus/LLM 调用失败 → 抛 unchecked 异常,`@Transactional` 回滚,不生成任何 task_step
- TDD:每个任务先写失败测试,再实现,再绿,再提交
- 现有 19 个测试不得回归

## Spec Reference

设计文档:`docs/superpowers/specs/2026-06-30-real-clients-design.md`

## File Structure

```
src/main/java/com/factory/ai/
  gitnexus/
    GitNexusClient.java                  -- 已有接口,不动
    GitNexusException.java               -- NEW: MCP 调用失败异常
    SpringAiMcpGitNexusClient.java       -- NEW: 真实实现
    dto/                                 -- 已有 DTO,不动
  task/
    service/
      LlmGateway.java                   -- 已有接口,不动
      LlmException.java                 -- NEW: LLM 调用失败异常
      LlmPromptBuilder.java             -- NEW: prompt 拼装工具
      SpringAiLlmGateway.java           -- NEW: 真实实现
      TaskDecompositionService.java      -- 修改:空草稿 → DECOMPOSING_FAILED
    web/
      TaskController.java                -- 修改:加 @ExceptionHandler
      dto/ErrorResponse.java            -- NEW: 错误响应 record
src/main/resources/
  application.yml                       -- 修改:加 spring.ai.* + factory.clients.*
  application-test.yml                  -- 修改:排除 Spring AI auto-config
  application-smoke.yml                 -- NEW: smoke profile 配置
src/test/java/com/factory/ai/
  gitnexus/
    SpringAiMcpGitNexusClientTest.java  -- NEW: 契约测试(mock McpSyncClient)
  task/service/
    LlmPromptBuilderTest.java           -- NEW: 纯单元测试
    SpringAiLlmGatewayTest.java         -- NEW: 契约测试(mock ChatClient)
    TaskDecompositionServiceTest.java   -- 修改:加空草稿测试
  task/web/
    TaskControllerTest.java             -- 修改:加 503 测试
  task/integration/
    SmokeTest.java                      -- NEW: @Tag("smoke") 真实端点
```

---

### Task 1: 升级 Spring Boot + 加 Spring AI 依赖 + 测试 profile 隔离

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application-test.yml`

**Interfaces:**
- Consumes: 无
- Produces: Spring AI 1.0.0 依赖在 classpath;测试 profile 排除 Spring AI auto-config,现有 19 个测试继续绿

- [ ] **Step 1: 修改 pom.xml — 升级 Spring Boot + 加 Spring AI BOM + starters**

```xml
<!-- pom.xml: 改 parent 版本 -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.5</version>
    <relativeTo/>
</parent>

<!-- 在 <dependencies> 前加 <dependencyManagement> -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- 在 <dependencies> 里加(现有依赖之后) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

> 注:如果 Spring Boot 3.4.5 不可用,用 3.4.x 最新 patch。`mvn dependency:tree | grep spring-boot` 确认。

- [ ] **Step 2: 修改 application-test.yml — 排除 Spring AI auto-config + 关闭真实 client**

```yaml
# src/main/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
  flyway:
    enabled: false
  autoconfigure:
    exclude:
      - org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration
      - org.springframework.ai.model.openapi.autoconfigure.OpenAiChatAutoConfiguration
factory:
  clients:
    real:
      enabled: false
```

> 注:自动配置类名以 Spring AI 1.0 实际为准。若上述类名不存在,运行 `mvn spring-boot:run -Dtest` 看启动报错,或 `mvn dependency:tree | grep spring-ai-autoconfigure` 找 jar,然后 `jar tf` 搜 `AutoConfiguration`。关键:测试 profile 下不能让 MCP client 尝试连 localhost:4747。

- [ ] **Step 3: 运行现有 19 个测试验证无回归**

Run: `mvn test`
Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

如果失败:
- 若是 auto-config 类名错 → 用 `jar tf` 找正确类名
- 若是 Spring Boot 3.4 不兼容 → 检查 Hibernate/Flyway 版本

- [ ] **Step 4: 提交**

```bash
git add pom.xml src/main/resources/application-test.yml
git commit -m "chore: upgrade Spring Boot 3.4.5 + add Spring AI 1.0 deps, isolate test profile"
```

---

### Task 2: 生产配置 + smoke profile

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-smoke.yml`

**Interfaces:**
- Consumes: Task 1 的 pom 依赖
- Produces: `spring.ai.mcp.client.*` + `spring.ai.openai.*` 配置;`factory.clients.real.enabled=true` 在默认 profile;smoke profile 指向真实端点

- [ ] **Step 1: 修改 application.yml — 加 Spring AI + factory 配置**

先读现有内容:
```bash
cat src/main/resources/application.yml
```

在现有内容后追加(保留原有 datasource/jpa/flyway 配置不动):
```yaml
  # Spring AI 配置
  ai:
    mcp:
      client:
        # 注:具体 key 名以 Spring AI 1.0 MCP client auto-config 文档为准
        # 若 http 不对,试 sse 或 streamable-http
        http:
          url: ${GITNEXUS_MCP_URL:http://localhost:4747/api/mcp}
    openai:
      base-url: ${LLM_BASE_URL:http://localhost:11434/v1}
      api-key: ${LLM_API_KEY:dummy}
      chat:
        options:
          model: ${LLM_MODEL:qwen2.5:14b}
          temperature: 0.2

factory:
  clients:
    real:
      enabled: true
```

> 注:`spring.ai.mcp.client.http.url` 的确切属性 key 需核实。若启动报"unknown property",查 Spring AI MCP auto-config 源码的 `@ConfigurationProperties` 前缀。备选 key:`spring.ai.mcp.client.sse.url`(老 SSE transport)或 `spring.ai.mcp.client.streamable-http.url`。

- [ ] **Step 2: 创建 application-smoke.yml**

```yaml
# src/main/resources/application-smoke.yml
# smoke 测试用:连真实 GitNexus + 真实 LLM,不连 DB(用 H2 内存)
spring:
  datasource:
    url: jdbc:h2:mem:smoketest;MODE=MySQL;DB_CLOSE_DELAY=-1
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
  flyway:
    enabled: false
  # 不排除 auto-config — smoke 测试要连真实端点
  ai:
    mcp:
      client:
        http:
          url: http://localhost:4747/api/mcp
    openai:
      base-url: http://localhost:11434/v1
      api-key: dummy
      chat:
        options:
          model: qwen2.5:14b
          temperature: 0.2

factory:
  clients:
    real:
      enabled: true
```

- [ ] **Step 3: 运行测试确认配置不破坏 test profile**

Run: `mvn test`
Expected: `Tests run: 19, Failures: 0, Errors: 0` — BUILD SUCCESS(test profile 仍用排除 auto-config,application.yml 的新增不影响 test profile)

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/application.yml src/main/resources/application-smoke.yml
git commit -m "feat: add Spring AI MCP/OpenAI config for production + smoke profile"
```

---

### Task 3: 异常类

**Files:**
- Create: `src/main/java/com/factory/ai/gitnexus/GitNexusException.java`
- Create: `src/main/java/com/factory/ai/task/service/LlmException.java`

**Interfaces:**
- Consumes: 无
- Produces: `GitNexusException`(MCP 调用失败)、`LlmException`(LLM 调用失败)— 后续 client 实现 + controller handler 依赖

- [ ] **Step 1: 创建 GitNexusException**

```java
// src/main/java/com/factory/ai/gitnexus/GitNexusException.java
package com.factory.ai.gitnexus;

public class GitNexusException extends RuntimeException {
    public GitNexusException(String message) { super(message); }
    public GitNexusException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 2: 创建 LlmException**

```java
// src/main/java/com/factory/ai/task/service/LlmException.java
package com.factory.ai.task.service;

public class LlmException extends RuntimeException {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: 无错

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/factory/ai/gitnexus/GitNexusException.java \
  src/main/java/com/factory/ai/task/service/LlmException.java
git commit -m "feat: add GitNexusException and LlmException for upstream failures"
```

---

### Task 4: SpringAiMcpGitNexusClient 实现 + 契约测试

**Files:**
- Create: `src/main/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClient.java`
- Test: `src/test/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClientTest.java`

**Interfaces:**
- Consumes: `McpSyncClient`(Spring AI MCP SDK,`callTool(CallToolRequest)` → `CallToolResult`);`GitNexusException`(Task 3);现有 DTO(`QueryResult`/`SymbolContext`/`ImpactResult`/`SymbolRef`)
- Produces: `GitNexusClient` 的真实实现 bean(`@ConditionalOnProperty`),4 方法 `query/context/impact/detectChanges`

- [ ] **Step 1: 写失败测试 — query 映射 MCP text 到 QueryResult**

```java
// src/test/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClientTest.java
package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;
import io.modelcontextprotocol.sdk.client.McpSyncClient;
import io.modelcontextprotocol.sdk.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiMcpGitNexusClientTest {

    @Mock McpSyncClient mcpClient;

    @Test
    void queryMapsMcpTextToQueryResult() {
        // MCP query 工具返回(简化):{"symbols":[{"name":"UserService","filePath":"src/UserService.java","kind":"Class"}],"processes":[]}
        // 但实际 query 工具返回按 process 分组,需拍平。测试用简化的 flat 结构先验证映射逻辑
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
```

> 注:`CallToolResult`/`TextContent`/`CallToolRequest` 的构造器签名以 MCP SDK 1.0 实际为准。若构造器不同(如 `CallToolResult` 用 `content` 而非 `List<Content>`),调整 helper。`TextContent` 可能在 `io.modelcontextprotocol.sdk.types` 包。若找不到,`jar tf ~/.m2/repository/io/modelcontextprotocol/mcp/*/mcp-*.jar | grep -i text` 定位。

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=SpringAiMcpGitNexusClientTest`
Expected: FAIL — `SpringAiMcpGitNexusClient` 类不存在

- [ ] **Step 3: 写 SpringAiMcpGitNexusClient 实现**

```java
// src/main/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClient.java
package com.factory.ai.gitnexus;

import com.factory.ai.gitnexus.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.client.McpSyncClient;
import io.modelcontextprotocol.sdk.types.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

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
        // MCP query 返回按 process 分组,拍平
        List<SymbolRef> symbols = new ArrayList<>();
        List<String> processNames = new ArrayList<>();
        for (JsonNode proc : root.path("processes")) {
            String label = proc.path("heuristicLabel").asText("");
            if (!label.isEmpty()) processNames.add(label);
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
            if (c instanceof TextContent tc) sb.append(tc.text());
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
        if (!arr.isArray()) return List.of();
        List<SymbolRef> list = new ArrayList<>();
        for (JsonNode n : arr) list.add(parseSymbolRef(n));
        return list;
    }

    private Integer optInt(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asInt();
    }
}
```

> 注:
> - `CallToolRequest` 构造器可能是 `new CallToolRequest(name, arguments)` 或 builder。若编译错,查 SDK 源码。
> - `CallToolResult.content()` 返回 `List<Content>`;`isError()` 返回 `Boolean`(可能 null)。`Content` 是 sealed interface,`TextContent` 是实现。
> - `TextContent` 可能用 `text()` 或 `value()` 方法。若 `tc.text()` 编译错,试 `tc.value()` 或查 jar。
> - Jackson `ObjectMapper` 已在 classpath(Spring Boot 自带)。

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=SpringAiMcpGitNexusClientTest`
Expected: PASS — 7 个测试全绿

若失败:
- MCP SDK 类型不匹配 → 用 `jar tf` 查实际类型名
- JSON 字段名不匹配 → 调整 `path("...")` 调用

- [ ] **Step 5: 运行全量测试确认无回归**

Run: `mvn test`
Expected: `Tests run: 26, Failures: 0, Errors: 0`(原 19 + 新 7)

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClient.java \
  src/test/java/com/factory/ai/gitnexus/SpringAiMcpGitNexusClientTest.java
git commit -m "feat: add SpringAiMcpGitNexusClient with MCP JSON-RPC to DTO mapping"
```

---

### Task 5: LlmPromptBuilder + 单元测试

**Files:**
- Create: `src/main/java/com/factory/ai/task/service/LlmPromptBuilder.java`
- Test: `src/test/java/com/factory/ai/task/service/LlmPromptBuilderTest.java`

**Interfaces:**
- Consumes: `QueryResult`(已有 DTO)
- Produces: `LlmPromptBuilder.buildUserMessage(String requirement, QueryResult queryResult)` → `String`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/factory/ai/task/service/LlmPromptBuilderTest.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmPromptBuilderTest {

    private final LlmPromptBuilder builder = new LlmPromptBuilder();

    @Test
    void buildsMessageWithRequirementAndSymbols() {
        var symbols = List.of(
            new SymbolRef("uid1", "UserService", "src/UserService.java", 1, 100),
            new SymbolRef("uid2", "UserController", "src/UserController.java", 1, 50)
        );
        var processes = List.of("UserAuth");
        var queryResult = new QueryResult(symbols, processes);

        String msg = builder.buildUserMessage("增加VIP等级查询", queryResult);

        assertTrue(msg.contains("增加VIP等级查询"));
        assertTrue(msg.contains("UserService"));
        assertTrue(msg.contains("UserController"));
        assertTrue(msg.contains("src/UserService.java"));
        assertTrue(msg.contains("UserAuth"));
    }

    @Test
    void handlesEmptyQueryResult() {
        var queryResult = new QueryResult(List.of(), List.of());
        String msg = builder.buildUserMessage("test requirement", queryResult);

        assertTrue(msg.contains("test requirement"));
        assertTrue(msg.contains("(无)"));  // 空符号列表显示"(无)"
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=LlmPromptBuilderTest`
Expected: FAIL — 类不存在

- [ ] **Step 3: 写 LlmPromptBuilder 实现**

```java
// src/main/java/com/factory/ai/task/service/LlmPromptBuilder.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import com.factory.ai.gitnexus.dto.SymbolRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class LlmPromptBuilder {

    public static final String SYSTEM_PROMPT = """
        你是 AI Factory 的任务拆解器。你的职责:基于产品需求 + GitNexus 代码摸底结果,
        把需求拆成若干个可独立执行的开发任务草稿。

        # 输入
        - 需求:管理员提交的产品需求(自然语言)
        - 摸底结果:GitNexus query() 返回的相关符号列表 + 执行流名称

        # 输出规则
        1. 输出一个 JSON 数组,每个元素是一个任务草稿,字段:
           - stepName: 动词短语,描述这个任务做什么(如 "加getVipLevel方法")
           - targetSymbol: 真实符号名,**必须从摸底结果的符号列表中选取**,不得凭空发明
           - instruction: 给执行员工的指令,简明扼要
        2. 拆解原则:
           - 每个任务改一个符号(类或方法),粒度小、可独立验证
           - 跨符号的需求拆成多个任务(如改 Service + 改 Controller = 两个任务)
           - 不要拆得过细(改一个方法的签名 + 改它的实现 = 一个任务)
           - 不输出与需求无关的任务(不要"加日志""加测试"等噪音)
        3. targetSymbol 必须是摸底结果里出现过的符号名。摸底结果为空 → 输出空数组 []。
        4. 只输出 JSON 数组,不要任何其他文字、解释、markdown 代码块标记。
        """;

    public String buildUserMessage(String requirement, QueryResult queryResult) {
        String symbols = queryResult.symbols().stream()
            .map(s -> "- " + s.name() + " @ " + s.filePath())
            .collect(Collectors.joining("\n"));
        String processes = String.join(", ", queryResult.processNames());

        return """
            需求: %s

            GitNexus 摸底结果:
            相关符号:
            %s

            执行流: %s

            请按系统指令输出任务草稿 JSON 数组。
            """.formatted(
                requirement,
                symbols.isBlank() ? "(无)" : symbols,
                processes.isBlank() ? "(无)" : processes
            );
    }
}
```

> 注:`SymbolRef` record 的字段名是 `filePath()`(不是 `kind()`),查 DTO 确认。`SymbolRef` 定义:`record SymbolRef(String uid, String name, String filePath, Integer startLine, Integer endLine)` — 无 `kind` 字段。所以 prompt 里不显示 kind。

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=LlmPromptBuilderTest`
Expected: PASS — 2 个测试

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/factory/ai/task/service/LlmPromptBuilder.java \
  src/test/java/com/factory/ai/task/service/LlmPromptBuilderTest.java
git commit -m "feat: add LlmPromptBuilder with system prompt and user message assembly"
```

---

### Task 6: SpringAiLlmGateway 实现 + 契约测试

**Files:**
- Create: `src/main/java/com/factory/ai/task/service/SpringAiLlmGateway.java`
- Test: `src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java`

**Interfaces:**
- Consumes: `ChatClient.Builder`(Spring AI,auto-wired);`LlmPromptBuilder`(Task 5);`LlmException`(Task 3);`LlmGateway.TaskDraft`(已有 record)
- Produces: `LlmGateway` 的真实实现 bean(`@ConditionalOnProperty`),`splitTasks(requirement, queryResult)` → `List<TaskDraft>`

- [ ] **Step 1: 写失败测试 — 正常返回 TaskDraft 列表 + 异常包装**

```java
// src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringAiLlmGatewayTest {

    @Mock ChatModel chatModel;
    ChatClient chatClient;
    LlmPromptBuilder promptBuilder;

    @BeforeEach
    void setup() {
        chatClient = ChatClient.create(chatModel);
        promptBuilder = new LlmPromptBuilder();
    }

    @Test
    void splitsTasksReturnsDrafts() {
        // 注:ChatClient 的 fluent API 在单元测试里很难 mock(内部链式调用)。
        // 这个测试用 ChatModel mock 来间接控制 ChatClient 行为。
        // Spring AI 的 ChatClient.create(chatModel).prompt()...call() 最终调 chatModel.call(Prompt)。
        // 我们 mock chatModel.call(any()) 返回含 JSON 的 ChatResponse。
        //
        // 但 ChatResponse 结构复杂,构造繁琐。替代方案:用 @SpringBootTest + MockRestServiceServer
        // 模拟 OpenAI HTTP 端点。这里先写一个 smoke-level 单元测试验证 wiring。
        //
        // 实际上,ChatClient 的 entity() 用 BeanOutputConverter 反序列化。
        // 要测它,最实际的方式是集成测试(MockRestServiceServer 模拟 /v1/chat/completions)。
        // 此测试先验证:空 QueryResult → 调用 LLM → 返回空列表(或抛 LlmException)。
        //
        // 见 Task 6 Step 1b 的集成测试版。
        assertTrue(true, "placeholder — see Step 1b for real test");
    }
}
```

> **重要说明:** Spring AI `ChatClient` 的 fluent API(`.prompt().system().user().call().entity()`)在纯 Mockito 单元测试里极难 mock,因为链中每个方法返回不同的 builder 类型。最实际的测试方式是**用 `@SpringBootTest` + `MockRestServiceServer` 模拟 OpenAI HTTP 端点**,验证完整请求→响应→反序列化链路。下面 Step 1b 是真实测试。

- [ ] **Step 1b: 写真实的契约测试(用 MockRestServiceServer 模拟 OpenAI HTTP)**

```java
// src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class SpringAiLlmGatewayTest {

    @Autowired LlmGateway llm;

    @Test
    void splitsTasksReturnsDraftsFromOpenAiResponse() {
        // 这个测试需要 Spring AI OpenAI auto-config 在 test profile 下也被启用
        // 但 Task 1 排除了它。所以这个测试用 @TestConfiguration 提供一个手动构建的
        // SpringAiLlmGateway,其 ChatClient 由 MockRestServiceServer 支撑的 RestClient 构建。
        //
        // 实际上,最干净的方式是把这个测试改成纯单元测试,mock ChatClient 链。
        // 但 ChatClient 链太长。妥协方案:测 LlmPromptBuilder(已测)+ 测 SpringAiLlmGateway
        // 只在 smoke 测试里验证真实 LLM。
        //
        // 结论:SpringAiLlmGateway 的契约测试用 mock ChatClient 链,见下面简化版。
        assertTrue(true, "see simplified mock version below");
    }
}
```

> **设计决策:** `ChatClient` fluent API 的 mock 链虽繁琐但可行。放弃 HTTP mock 方案(太重),改用 Mockito mock `ChatClient` 链。下面是最终测试版。

- [ ] **Step 1c: 最终测试版 — mock ChatClient 链**

替换 `src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java` 全部内容:

```java
// src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringAiLlmGatewayTest {

    @Mock ChatClient.Builder chatClientBuilder;
    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequest promptSpec;
    @Mock ChatClient.CallResponseSpec callResponseSpec;

    @Test
    void splitsTasksReturnsDrafts() {
        // 准备 mock 链:builder.build() → chatClient;chatClient.prompt() → promptSpec;
        // promptSpec.system(any).user(any) → promptSpec;.call() → callResponseSpec;
        // callResponseSpec.entity(any(ParameterizedTypeReference)) → List<TaskDraft>
        var drafts = List.of(
            new LlmGateway.TaskDraft("加getVipLevel", "UserService", "在 UserService 加 getVipLevel"),
            new LlmGateway.TaskDraft("加HTTP接口", "UserController", "在 Controller 加接口")
        );

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(any(java.lang.reflect.ParameterizedType.class)))
            .thenReturn(drafts);

        var promptBuilder = new LlmPromptBuilder();
        var gateway = new SpringAiLlmGateway(chatClientBuilder, promptBuilder);

        var queryResult = new QueryResult(
            List.of(new SymbolRef("u1", "UserService", "src/UserService.java", 1, 100)),
            List.of("UserAuth")
        );

        var result = gateway.splitTasks("增加VIP等级查询", queryResult);

        assertEquals(2, result.size());
        assertEquals("加getVipLevel", result.get(0).stepName());
        assertEquals("UserService", result.get(0).targetSymbol());
    }

    @Test
    void wrapsExceptionsAsLlmException() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.user(any(String.class))).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(any(java.lang.reflect.ParameterizedType.class)))
            .thenThrow(new RuntimeException("LLM 500"));

        var promptBuilder = new LlmPromptBuilder();
        var gateway = new SpringAiLlmGateway(chatClientBuilder, promptBuilder);

        var queryResult = new QueryResult(List.of(), List.of());

        assertThrows(LlmException.class, () -> gateway.splitTasks("req", queryResult));
    }
}
```

> 注:`ChatClient.ChatClientRequest` 和 `ChatClient.CallResponseSpec` 的实际类型名以 Spring AI 1.0 为准。若编译错,查 `org.springframework.ai.chat.client.ChatClient` 源码或 jar。可能叫 `ChatClient.Request` 或 `ChatClient.PromptSpec`。`entity()` 方法可能接受 `ParameterizedTypeReference` 而非 `ParameterizedType`。调整 mock 签名以匹配实际 API。

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=SpringAiLlmGatewayTest`
Expected: FAIL — `SpringAiLlmGateway` 类不存在

- [ ] **Step 3: 写 SpringAiLlmGateway 实现**

```java
// src/main/java/com/factory/ai/task/service/SpringAiLlmGateway.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.dto.QueryResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.lang.reflect.ParameterizedType;
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
                .entity(new com.fasterxml.jackson.core.type.TypeReference<List<TaskDraft>>() {});
        } catch (Exception e) {
            throw new LlmException("LLM splitTasks failed for requirement: " + requirement, e);
        }
    }
}
```

> 注:
> - `entity(TypeReference)` 是 Spring AI 1.0 `CallResponseSpec` 的 structured output 方法。若签名是 `entity(Class)` 或 `entity(ParameterizedTypeReference)`,调整。`TypeReference` 来自 Jackson,Spring AI 通常接受它。
> - `ChatClient.Builder` 是 Spring AI auto-config 注入的 bean(test profile 排除 auto-config 时不存在,但 `@ConditionalOnProperty` 保证此 bean 不在 test profile 创建)。

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=SpringAiLlmGatewayTest`
Expected: PASS — 2 个测试

若 mock 链不匹配:
- 查 `ChatClient` 源码确认中间类型名
- 调整 mock 的 `when(...)` 链以匹配实际 fluent API

- [ ] **Step 5: 运行全量测试确认无回归**

Run: `mvn test`
Expected: `Tests run: 30, Failures: 0, Errors: 0`(原 19 + Task4 的 7 + Task5 的 2 + Task6 的 2)

> 注:SpringAiLlmGateway 在 test profile 下不创建(`@ConditionalOnProperty false`),所以现有 `@TestConfiguration @Bean @Primary LlmGateway` fake 仍正常注入。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/factory/ai/task/service/SpringAiLlmGateway.java \
  src/test/java/com/factory/ai/task/service/SpringAiLlmGatewayTest.java
git commit -m "feat: add SpringAiLlmGateway with ChatClient structured output for task drafts"
```

---

### Task 7: TaskController @ExceptionHandler + ErrorResponse

**Files:**
- Modify: `src/main/java/com/factory/ai/task/web/TaskController.java`
- Create: `src/main/java/com/factory/ai/task/web/dto/ErrorResponse.java`
- Modify: `src/test/java/com/factory/ai/task/web/TaskControllerTest.java`

**Interfaces:**
- Consumes: `GitNexusException` / `LlmException`(Task 3)
- Produces: `POST /tasks/decompose` 在上游失败时返回 `503 {"code":"UPSTREAM_UNAVAILABLE","message":"..."}`

- [ ] **Step 1: 写失败测试 — decompose 在 GitNexus 挂时返回 503**

在 `TaskControllerTest.java` 现有内容后追加(保留现有 2 个测试):

```java
    // 在类内追加(字段和 @MockBean 已有,不重复声明)

    @Test
    void decomposeReturns503WhenGitNexusDown() throws Exception {
        // TaskDecompositionService 会调 gitNexus.query() → 抛 GitNexusException
        // 但 TaskControllerTest 已 @MockBean GitNexusClient,默认 mock 不抛
        // 需要 stub:gitNexusClient.query(any(), any()) 抛 GitNexusException
        org.mockito.Mockito.doThrow(new com.factory.ai.gitnexus.GitNexusException("connection refused"))
            .when(gitNexusClient).query(any(), any());

        mvc.perform(post("/tasks/decompose")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirement\":\"test\",\"repo\":\"r\",\"adminId\":1}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("connection refused"));
    }
```

需在文件顶部加 import(若 IDE 未自动):
```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
```

> 注:TaskDecompositionService 是 `@Transactional`,抛 GitNexusException 时事务回滚。但 controller 的 `@ExceptionHandler` 捕获异常,返回 503。需确认 Spring MVC 的 `@ExceptionHandler` 在 `@Transactional` 回滚后仍能捕获——可以的,异常先传播到 controller 层,ExceptionHandler 捕获,事务在 service 层已回滚。

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=TaskControllerTest`
Expected: FAIL — `ErrorResponse` 不存在,`@ExceptionHandler` 不存在,返回 500 而非 503

- [ ] **Step 3: 创建 ErrorResponse + 加 @ExceptionHandler**

```java
// src/main/java/com/factory/ai/task/web/dto/ErrorResponse.java
package com.factory.ai.task.web.dto;

public record ErrorResponse(String code, String message) {}
```

修改 `TaskController.java`,在类内加:

```java
    // 在 TaskController 类内加(import 先加):
    // import com.factory.ai.gitnexus.GitNexusException;
    // import com.factory.ai.task.service.LlmException;
    // import com.factory.ai.task.web.dto.ErrorResponse;
    // import org.springframework.web.bind.annotation.ExceptionHandler;
    // import org.springframework.http.HttpStatus;

    @ExceptionHandler({GitNexusException.class, LlmException.class})
    public ResponseEntity<ErrorResponse> onUpstreamFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=TaskControllerTest`
Expected: PASS — 3 个测试(原 2 + 新 1)

- [ ] **Step 5: 运行全量测试**

Run: `mvn test`
Expected: 全绿

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/factory/ai/task/web/TaskController.java \
  src/main/java/com/factory/ai/task/web/dto/ErrorResponse.java \
  src/test/java/com/factory/ai/task/web/TaskControllerTest.java
git commit -m "feat: add 503 error handling for upstream GitNexus/LLM failures"
```

---

### Task 8: 空草稿 → DECOMPOSING_FAILED 逻辑

**Files:**
- Modify: `src/main/java/com/factory/ai/task/service/TaskDecompositionService.java`
- Modify: `src/test/java/com/factory/ai/task/service/TaskDecompositionServiceTest.java`

**Interfaces:**
- Consumes: `LlmGateway.splitTasks()`(可能返回空列表);`TaskStatus.DECOMPOSING_FAILED`(已有枚举)
- Produces: `decompose()` 在 LLM 返回空草稿时,存父 task(status=DECOMPOSING_FAILED)+ 不建 step + 返回 task id

- [ ] **Step 1: 写失败测试 — 空草稿返回 task id 且无 step**

在 `TaskDecompositionServiceTest.java` 现有 `@TestConfiguration` 和测试后追加:

```java
    @Test
    void emptyDraftsSetsDecomposingFailedAndNoSteps() {
        // 用一个返回空草稿的 LlmGateway bean 覆盖
        // 但现有 @TestConfiguration 的 LlmGateway 返回 2 个草稿
        // 需要用单独的测试方法 + 手动构造 service,或用 @DynamicProperty
        // 最简:直接调 decomp.decompose,但 decomp 用的是 @Primary LlmGateway(返回 2 草稿)
        //
        // 替代:手动 new TaskDecompositionService(...) 注入返回空的 LlmGateway
        // 但需要所有 repo 和 service 依赖——太重。
        //
        // 最实际:用 Mockito spy 或单独 @TestConfiguration
        // 这里用一个单独的 inner @TestConfiguration 方法不行(已有 @TestConfiguration class)
        //
        // 妥协:用 @MockBean LlmGateway 覆盖(在测试方法级)
        // 但 @SpringBootTest 的 bean 是共享的。最干净:新开一个测试类。
        //
        // 决定:此测试验证"空草稿 → DECOMPOSING_FAILED + 0 steps"
        // 用 decomp.decompose("需求", "repo", 1L) 但需要 LlmGateway 返回空
        // 直接用 ApplicationContext 拿 LlmGateway bean,用 Mockito.doReturn 覆盖
    }
```

> **设计决策:** 空草稿测试需要 LlmGateway 返回空列表,但 `TaskDecompositionServiceTest` 的 `@TestConfiguration` 提供的 LlmGateway 返回 2 个草稿。最干净的方式:在新测试类里用 `@MockBean LlmGateway` 覆盖。下面是实际测试。

- [ ] **Step 1b: 实际测试 — 新测试类或用 @MockBean 覆盖**

在 `TaskDecompositionServiceTest.java` 内追加(利用 Mockito 覆盖 `@Primary` bean):

```java
    @org.springframework.boot.test.mock.mockito.MockBean
    LlmGateway llmGateway;  // 覆盖 @TestConfiguration 的 @Primary LlmGateway

    @Test
    void emptyDraftsSetsDecomposingFailedAndNoSteps() {
        // stub:LLM 返回空草稿
        org.mockito.Mockito.when(llmGateway.splitTasks(any(), any()))
            .thenReturn(java.util.List.of());

        Long taskId = svc.decompose("需求查不到符号", "repo", 1L);

        var task = tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStatus.DECOMPOSING_FAILED, task.getStatus());

        var allSteps = steps.findAll().stream()
            .filter(s -> s.getTaskId().equals(taskId)).toList();
        assertTrue(allSteps.isEmpty(), "no steps should be created for empty drafts");
    }
```

> 注:`@MockBean LlmGateway` 会覆盖 `@TestConfiguration` 的 `@Bean @Primary LlmGateway`。但已有测试 `decomposeCreatesStepsWithDependencyAndContext` 依赖 `@TestConfiguration` 的 LlmGateway 返回 2 草稿。加 `@MockBean LlmGateway` 后,那个测试也会拿到 mock(返回 null)→ 失败。
>
> **解法:** 把 `@MockBean LlmGateway` 放到单独的测试类 `TaskDecompositionEmptyDraftTest` 里,不污染原测试类。

- [ ] **Step 1c: 新建独立测试类**

```java
// src/test/java/com/factory/ai/task/service/TaskDecompositionEmptyDraftTest.java
package com.factory.ai.task.service;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.*;
import com.factory.ai.task.domain.*;
import com.factory.ai.task.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TaskDecompositionEmptyDraftTest {

    @Autowired TaskDecompositionService svc;
    @Autowired TaskRepository tasks;
    @Autowired TaskStepRepository steps;

    @MockBean LlmGateway llmGateway;

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary GitNexusClient gitNexus() {
            return new GitNexusClient() {
                public QueryResult query(String q, String r) {
                    return new QueryResult(List.of(), List.of());  // 空摸底
                }
                public SymbolContext context(String n, String r) { return null; }
                public ImpactResult impact(String t, String d, String r) {
                    return new ImpactResult(t, d, "LOW", java.util.Map.of());
                }
                public boolean detectChanges(String r) { return true; }
            };
        }
    }

    @Test
    void emptyDraftsSetsDecomposingFailedAndNoSteps() {
        when(llmGateway.splitTasks(any(), any())).thenReturn(List.of());

        Long taskId = svc.decompose("需求查不到符号", "repo", 1L);

        var task = tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStatus.DECOMPOSING_FAILED, task.getStatus());

        var allSteps = steps.findAll().stream()
            .filter(s -> s.getTaskId().equals(taskId)).toList();
        assertTrue(allSteps.isEmpty(), "no steps should be created for empty drafts");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=TaskDecompositionEmptyDraftTest`
Expected: FAIL — `decompose()` 在空草稿时仍走原逻辑(建 0 step,设 `task.status = READY`),不是 `DECOMPOSING_FAILED`

- [ ] **Step 3: 修改 TaskDecompositionService — 加空草稿分支**

在 `decompose()` 方法中,在 LLM 拆解之后、建 step 之前加空草稿检查:

```java
    @Transactional
    public Long decompose(String requirement, String repo, Long adminId) {
        // 1. 建父任务
        Task task = taskRepo.save(new Task(requirement, adminId));

        // 2. query 摸底
        QueryResult queryResult = gitNexus.query(requirement, repo);

        // 3. LLM 拆解
        List<LlmGateway.TaskDraft> drafts = llm.splitTasks(requirement, queryResult);

        // 3.5 空草稿 → DECOMPOSING_FAILED,不建 step
        if (drafts.isEmpty()) {
            task.setStatus(TaskStatus.DECOMPOSING_FAILED);
            taskRepo.save(task);
            return task.getId();
        }

        // 4. 建步骤实体(先存,拿到 ID)
        List<TaskStep> stepList = new ArrayList<>();
        for (var d : drafts) {
            TaskStep s = new TaskStep(task.getId(), d.stepName(), d.targetSymbol());
            stepList.add(stepRepo.save(s));
        }

        // 5-7. 派生依赖 + 聚合 + 父任务就绪(原逻辑不变)
        var edges = derivationSvc.derive(stepList, repo);
        depRepo.saveAll(edges);

        for (TaskStep s : stepList) {
            aggregationSvc.aggregate(s, repo, requirement);
            s.setStatus(s.getDependsOnCount() == 0 ? TaskStepStatus.READY : TaskStepStatus.PENDING);
            stepRepo.save(s);
        }

        boolean anyReview = stepList.stream().anyMatch(TaskStep::isNeedsReview);
        task.setStatus(anyReview ? TaskStatus.PARTIAL : TaskStatus.READY);
        taskRepo.save(task);
        return task.getId();
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=TaskDecompositionEmptyDraftTest`
Expected: PASS

- [ ] **Step 5: 运行全量测试确认无回归**

Run: `mvn test`
Expected: 全绿(原 `decomposeCreatesStepsWithDependencyAndContext` 仍正常,因为它用的 LlmGateway 返回 2 草稿)

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/factory/ai/task/service/TaskDecompositionService.java \
  src/test/java/com/factory/ai/task/service/TaskDecompositionEmptyDraftTest.java
git commit -m "feat: set DECOMPOSING_FAILED on empty LLM drafts, no steps created"
```

---

### Task 9: Smoke 集成测试(可选,真实端点)

**Files:**
- Create: `src/test/java/com/factory/ai/task/integration/SmokeTest.java`

**Interfaces:**
- Consumes: 真实 GitNexus(`localhost:4747/api/mcp`)+ 真实 LLM(`localhost:11434/v1`);`TaskDecompositionService`
- Produces: `@Tag("smoke")` 测试,默认跳过,`mvn test -Dgroups=smoke` 才跑

- [ ] **Step 1: 创建 smoke 测试**

```java
// src/test/java/com/factory/ai/task/integration/SmokeTest.java
package com.factory.ai.task.integration;

import com.factory.ai.task.repository.*;
import com.factory.ai.task.service.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@Tag("smoke")
@SpringBootTest
@ActiveProfiles("smoke")
@EnabledIfEnvironmentVariable(named = "SMOKE", matches = "true")
class SmokeTest {

    @Autowired TaskDecompositionService decomp;
    @Autowired TaskRepository tasks;
    @Autowired TaskStepRepository steps;

    @Test
    void decomposeWithRealGitNexusAndLlm() {
        // 前提:GitNexus 在 localhost:4747 跑,索引了某 repo
        //       LLM 在 localhost:11434 跑,模型 qwen2.5:14b 已拉
        Long taskId = decomp.decompose("在某个已索引的 repo 里加一个简单方法", "repo", 1L);

        var task = tasks.findById(taskId).orElseThrow();
        // 要么成功(READY,有 step),要么空草稿(DECOMPOSING_FAILED)
        assertTrue(
            task.getStatus().name().equals("READY") || task.getStatus().name().equals("DECOMPOSING_FAILED"),
            "task should be READY or DECOMPOSING_FAILED, got: " + task.getStatus()
        );

        if (task.getStatus().name().equals("READY")) {
            var taskSteps = steps.findAll().stream()
                .filter(s -> s.getTaskId().equals(taskId)).toList();
            assertFalse(taskSteps.isEmpty());
            assertNotNull(taskSteps.get(0).getGeneratedPrompt());
        }
    }
}
```

- [ ] **Step 2: 确认默认 mvn test 跳过 smoke**

Run: `mvn test`
Expected: `Tests run: <N>, Skipped: 0`(SmokeTest 不出现——`@EnabledIfEnvironmentVariable` 在 SMOKE 未设时跳过)

若 SmokeTest 出现为 skipped,加 surefire 配置排除(在 pom.xml `<build><plugins>` 加):
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludedGroups>smoke</excludedGroups>
    </configuration>
</plugin>
```

- [ ] **Step 3: 手动跑 smoke(需本地 GitNexus + LLM 在线)**

```bash
SMOKE=true mvn test -Dtest=SmokeTest -Dgroups=smoke
```
Expected: PASS(或根据真实数据返回 DECOMPOSING_FAILED 也算通过)

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/factory/ai/task/integration/SmokeTest.java pom.xml
git commit -m "test: add smoke integration test for real GitNexus + LLM (skipped by default)"
```

---

## Self-Review

**1. Spec coverage:**
- §1 技术选型 → Task 1 (Spring Boot 升级 + Spring AI 依赖) ✓
- §2 架构 → Task 4 + 6 (两个 client 实现) ✓
- §3 MCP 工具映射 → Task 4 (4 方法 + DTO 映射) ✓
- §3.1 JSON-RPC payload → Task 4 (`callTool` + `extractText` + 二次反序列化) ✓
- §4 LLM 接入 → Task 5 (prompt builder) + Task 6 (gateway) ✓
- §4.1 SYSTEM_PROMPT → Task 5 (`LlmPromptBuilder.SYSTEM_PROMPT`) ✓
- §4.2 用户消息 → Task 5 (`buildUserMessage`) ✓
- §4.3 structured output → Task 6 (`.entity(TypeReference)`) ✓
- §5 失败处理 → Task 3 (异常类) + Task 7 (ExceptionHandler) ✓
- §5.3 空草稿 → Task 8 (DECOMPOSING_FAILED) ✓
- §6 配置 → Task 2 (application.yml + smoke) ✓
- §7 测试策略 → Task 4/6 (契约测试) + Task 9 (smoke) ✓
- §8 实现任务切分 → Task 1-9 ✓
- §9 边界 → Task 8 (空草稿) + Task 7 (503) ✓

**2. Placeholder scan:**
- Task 6 Step 1/1b 有"placeholder"注释但已被 Step 1c 替换。保留 Step 1/1b 作为"为何用 mock 而非 HTTP"的设计说明,最终代码是 Step 1c。不算占位符。
- 无 TBD/TODO/"implement later"。所有代码步骤含完整代码。✓
- 多处 `> 注:` 是实现时需核实的 API 细节(MCP SDK 类型名、Spring AI 属性 key),不是设计占位符。每个都给了定位方法(`jar tf`、查源码)。✓

**3. Type consistency:**
- `GitNexusClient` 4 方法签名:Task 4 实现 与 原接口一致 ✓
- `LlmGateway.splitTasks(String, QueryResult)` → `List<TaskDraft>`:Task 6 实现与原接口一致 ✓
- `TaskDraft` record:`stepName/targetSymbol/instruction` — Task 5/6/8 一致 ✓
- `GitNexusException`/`LlmException`:Task 3 定义,Task 4/6/7 使用,构造器签名一致 ✓
- `LlmPromptBuilder.SYSTEM_PROMPT` + `buildUserMessage`:Task 5 定义,Task 6 调用 ✓
- `ErrorResponse(String code, String message)`:Task 7 定义并使用 ✓
- `TaskStatus.DECOMPOSING_FAILED`:Task 8 使用,Task 1 已存在的枚举值 ✓
- `@ConditionalOnProperty(name = "factory.clients.real.enabled", havingValue = "true")`:Task 4/6 一致,Task 2 配置 property ✓
