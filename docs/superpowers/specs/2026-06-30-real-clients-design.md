# GitNexus AI Factory — 真实客户端接入设计

> 版本: V6.2 (真实客户端接入)
> 日期: 2026-06-30
> 状态: 待审阅
> 父文档: GitNexus AI Factory - 跨任务依赖模型 (V6.1)

## 0. 目标

把 V6.1 中 stub 的 `GitNexusClient`(4 方法全 mock)和 `LlmGateway`(无实现)替换为真实可用的客户端实现,让 `POST /tasks/decompose` 端到端跑通真实数据。

**核心原则(用户锁定)**:不降级。GitNexus / LLM 任何调用失败 → 让异常抛出,事务回滚,不生成任何任务。完全基于 GitNexus 的真实数据,查不到就不生成。

## 1. 技术选型(已锁定)

| 决策点 | 选定方案 | 理由 |
|---|---|---|
| 客户端栈 | Spring AI 1.0 GA | MCP transport + OpenAI starter 框架已封装;手写 JSON-RPC 边角案例多 |
| Spring Boot 版本 | 升级 3.2.5 → 3.4.x | Spring AI 1.0 GA 要求;小项目 vanilla 配置,升级成本低 |
| GitNexus 协议 | MCP-over-HTTP(`http://localhost:4747/api/mcp`) | 已部署的 MCP server,Java 端用 Spring AI MCP client 直连 |
| LLM provider | OpenAI 兼容接口 + 本地模型 | 用户选定;Spring AI OpenAI starter 配 `base-url` 即可指向 Ollama / LM Studio / 兼容端点 |
| 失败处理 | 无降级,失败即抛 | 用户锁定;事务回滚,不假装成功 |

## 2. 架构

```
┌─────────────────────────────────────────────────────────────────┐
│ Spring Boot 3.4.x app (com.factory.ai)                          │
│                                                                  │
│  Spring AI auto-config:                                          │
│  ┌──────────────────────────┐  ┌──────────────────────────┐     │
│  │ McpClient (auto-wired)   │  │ ChatClient.Builder       │     │
│  │  ← spring-ai-starter-    │  │  ← spring-ai-starter-    │     │
│  │    mcp-client             │  │    openai                 │     │
│  │  配置: localhost:4747/api │  │  配置: base-url,          │     │
│  │       /mcp (HTTP)         │  │  api-key, model           │     │
│  └──────────┬───────────────┘  └──────────┬───────────────┘     │
│             │ injected                     │ injected             │
│             ↓                              ↓                      │
│  ┌──────────────────────────┐  ┌──────────────────────────┐     │
│  │ SpringAiMcpGitNexusClient│  │ SpringAiLlmGateway       │     │
│  │  implements GitNexusClient│  │  implements LlmGateway    │     │
│  │  (4 方法 → callTool)      │  │  (splitTasks → ChatClient│     │
│  │  + JSON → DTO 映射        │  │   structured output)     │     │
│  └──────────┬───────────────┘  └──────────┬───────────────┘     │
│             │                              │                      │
│             ↓                              ↓                      │
│  现有 service 层不动(TaskDecompositionSvc 等)                  │
└──────────────┼──────────────────────────────────┼───────────────┘
               │ HTTP (JSON-RPC over MCP)         │ HTTP (OpenAI API)
               ↓                                  ↓
   http://localhost:4747/api/mcp       http://localhost:11434/v1
   (GitNexus MCP server)               (Ollama/LM Studio/兼容端点)
```

**新增组件**:
1. `SpringAiMcpGitNexusClient` — 实现 `GitNexusClient`,注入 Spring AI 的 `McpClient`,4 方法映射到 `callTool(...)`,把返回 JSON 映射到现有 DTO。
2. `SpringAiLlmGateway` — 实现 `LlmGateway`,注入 `ChatClient`,用 Spring AI structured output(`BeanOutputConverter`)直接返回 `List<TaskDraft>`。
3. `LlmPromptBuilder` — 把 `requirement` + `QueryResult` 拼成 system/user prompt 段。
4. 配置:`application.yml` 加 `spring.ai.mcp.client.*` + `spring.ai.openai.*`。`application-test.yml` 不动(继续用 fake beans)。
5. 异常类:`GitNexusException`、`LlmException`(unchecked)。
6. `ErrorResponse` + `@ExceptionHandler` 在 `TaskController`。

**不改动**:domain / repository / 现有 service 业务逻辑 / 现有测试断言。现有 19 个测试继续用 `@TestConfiguration` 的 fake bean,不连真实端点。

## 3. GitNexus MCP 工具 → Java 方法映射

从本会话 `mcp__gitnexus__*` 工具定义核实真实参数:

| Java 方法 | MCP 工具 | 入参 | 返回 → DTO 映射 |
|---|---|---|---|
| `query(q, repo)` | `query` | `{query, repo, limit:5, max_symbols:10}` | `processes[].symbols` 拍平 → `SymbolRef` 列表;`processes[].heuristicLabel` → `processNames`。MCP 按 process 分组,Java DTO 扁平——实现时拍平 |
| `context(name, repo)` | `context` | `{name, repo, include_content:true}` | 顶层 `uid/name/kind/filePath/startLine/endLine/sourceContent` → `SymbolContext`;`incomingCalls`(reason 含 'read'/'calls')→ `incomingCalls`;`outgoingMethods` → `outgoingMethods` |
| `impact(target, dir, repo)` | `impact` | `{target, direction, repo, maxDepth:3, minConfidence:0.7}` | `target/direction/risk` 直映;`byDepth`(`Map<Integer, List<SymbolRef>>`)直接反序列化 |
| `detectChanges(repo)` | `detect_changes` | `{repo, scope:"unstaged"}` | MCP 返回 `{changed_symbols, affected_processes, risk}`;Java 只要 boolean:有非空 `changed_symbols` → true,否则 false |

**DTO 不扩**:`QueryResult` / `SymbolContext` / `ImpactResult` / `SymbolRef` 现有字段够 prompt 组装用。MCP 富数据(affected_processes、affected_modules、confidence 等)v1 丢弃,未来需要再扩。

### 3.1 JSON-RPC payload 形态

Spring AI `McpClient.callTool(...)` 封装 JSON-RPC 外层 + id 关联。MCP `tools/call` 返回:

```json
{
  "jsonrpc": "2.0", "id": 1,
  "result": {
    "content": [
      { "type": "text", "text": "<JSON string of tool output>" }
    ],
    "isError": false
  }
}
```

**关键**:`content[0].text` 是字符串化的 JSON,要二次反序列化。`SpringAiMcpGitNexusClient` 核心逻辑:`callTool` → 取 `content[0].text` → `objectMapper.readValue(...)` → DTO。`isError:true` → 抛 `GitNexusException`。

## 4. LLM 接入

### 4.1 SYSTEM_PROMPT

```text
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
```

### 4.2 用户消息构造(`LlmPromptBuilder`)

```java
String buildUserMessage(String requirement, QueryResult queryResult) {
    var symbols = queryResult.symbols().stream()
        .map(s -> "- " + s.name() + " (" + s.kind() + ") @ " + s.filePath())
        .collect(joining("\n"));
    var processes = String.join(", ", queryResult.processNames());
    return """
        需求: %s

        GitNexus 摸底结果:
        相关符号:
        %s

        执行流: %s

        请按系统指令输出任务草稿 JSON 数组。
        """.formatted(requirement,
            symbols.isBlank() ? "(无)" : symbols,
            processes.isBlank() ? "(无)" : processes);
}
```

### 4.3 structured output 调用

```java
List<TaskDraft> drafts = chatClient.prompt()
    .system(SYSTEM_PROMPT)
    .user(promptBuilder.buildUserMessage(requirement, queryResult))
    .call()
    .entity(new ParameterizedTypeReference<List<TaskDraft>>() {});
```

`TaskDraft` record 已存在(Task 6 定义)。Spring AI `BeanOutputConverter` 自动注入 JSON schema 指令并反序列化——无需手写 JSON 解析。

## 5. 失败处理(无降级,用户锁定)

**原则**:GitNexus / LLM 任何调用失败 → 让异常抛出,事务回滚,不生成任何任务。

| 调用点 | 失败行为 | 结果 |
|---|---|---|
| `decompose()` → `gitNexus.query()` | 抛 `GitNexusException` | `@Transactional` 回滚 → 无 task/step/dep 持久化 → controller 返 503 |
| `decompose()` → `llm.splitTasks()` | 抛 `LlmException` | 同上 |
| `decompose()` → `gitNexus.impact()` (DependencyDerivationService) | 抛 `GitNexusException` | 同上 |
| `decompose()` → `gitNexus.context()` (ContextAggregationService) | 抛 `GitNexusException` | 同上 |
| `complete()` → `gitNexus.detectChanges()` 网络炸 | 抛 `GitNexusException` | `@Transactional` 回滚,step 仍 IN_PROGRESS,员工可重试 |
| `complete()` → `detectChanges` 返回 `false`(查询成功但无改动) | 返回 `false` | 这是"查到了真实数据,数据说没改" → 正常拒绝,不算失败 |

**关键区分**:`GitNexusException` = "查不到/网络炸/`isError:true`" → 抛;`detectChanges` 返回 `false` = "查到了,数据说没改" → 正常返回 false。两者不混淆。

### 5.1 异常类型

```java
// com.factory.ai.gitnexus.GitNexusException
public class GitNexusException extends RuntimeException {
    public GitNexusException(String msg, Throwable cause) { super(msg, cause); }
}

// com.factory.ai.task.service.LlmException
public class LlmException extends RuntimeException {
    public LlmException(String msg, Throwable cause) { super(msg, cause); }
}
```

### 5.2 Controller 错误响应

```java
// TaskController 加:
@ExceptionHandler({GitNexusException.class, LlmException.class})
ResponseEntity<ErrorResponse> onUpstreamFailure(RuntimeException e) {
    return ResponseEntity.status(503)
        .body(new ErrorResponse("UPSTREAM_UNAVAILABLE", e.getMessage()));
}

// record ErrorResponse(String code, String message) {}
```

管理员调 `/decompose` 时 GitNexus 挂了 → `503 {"code":"UPSTREAM_UNAVAILABLE","message":"..."}`,不是 500 stacktrace。

### 5.3 空草稿处理

LLM 按规则 3,摸底为空时返回空数组 `[]`。`TaskDecompositionService.decompose()` 收到空草稿时:
- **不建任何 task_step**("不生成任务"指不生成 step——真实数据说没东西可拆,符合"完全基于真实数据")
- 父 task 仍存作为审计记录,status 设 `DECOMPOSING_FAILED`
- 返回 task id,管理员可见"需求拆解为 0 任务"的失败状态

`TaskStatus.DECOMPOSING_FAILED` 枚举值在此终于有用例。**这不是降级**——LLM 调用本身成功了,真实数据就是 0 个可拆任务,系统如实记录。

## 6. 配置

### 6.1 `application.yml`(新增 spring.ai 段)

```yaml
spring:
  ai:
    mcp:
      client:
        http:
          url: http://localhost:4747/api/mcp
          enabled: true
        name: gitnexus
    openai:
      base-url: ${LLM_BASE_URL:http://localhost:11434/v1}
      api-key: ${LLM_API_KEY:dummy}
      chat:
        options:
          model: ${LLM_MODEL:qwen2.5:14b}
          temperature: 0.2
```

> 注:具体 `spring.ai.mcp.client.*` key 名以 Spring AI 1.0 文档为准,实现时核实。

### 6.2 `application-test.yml`(不动)

继续用 `@MockBean` / `@TestConfiguration` 提供 fake,测试不连真实端点。

### 6.3 `application-smoke.yml`(新增,profile=smoke)

同 6.1,指向真实端点,只给 smoke 集成测试用。

## 7. 测试策略

| 层 | 测试 | 工具 | 连真实端点? |
|---|---|---|---|
| 业务逻辑(现有 19 个) | 不改,继续用 fake `GitNexusClient`/`LlmGateway` | `@TestConfiguration` fake beans | 否 |
| `SpringAiMcpGitNexusClient` 契约 | 验证 JSON-RPC payload + DTO 映射 + 异常抛出 | `MockRestServiceServer` 模拟 MCP HTTP | 否 |
| `SpringAiLlmGateway` 契约 | 验证 structured output 解析 + 异常抛出 | `MockRestServiceServer` 模拟 OpenAI HTTP | 否 |
| `LlmPromptBuilder` | 验证 prompt 拼装 | 纯单元测试 | 否 |
| Smoke 端到端 | 真实 GitNexus + 真实 LLM | `@Tag("smoke")` 默认跳过,`-Dgroups=smoke` 才跑 | 是 |

## 8. 实现任务切分(骨架,交 writing-plans 细化)

| # | 任务 | 主要文件 | 依赖 |
|---|---|---|---|
| 1 | 升级 Spring Boot 3.4.x + 加 Spring AI 依赖 | `pom.xml` | 无 |
| 2 | 配置 + 配置属性 | `application.yml` | 1 |
| 3 | 异常类 | `GitNexusException`, `LlmException` | 无 |
| 4 | `SpringAiMcpGitNexusClient` + DTO 映射 | 主类 | 1,2,3 |
| 5 | `SpringAiMcpGitNexusClient` 契约测试 | 测试 | 4 |
| 6 | `LlmPromptBuilder` + `SpringAiLlmGateway` | 2 主类 | 1,2,3 |
| 7 | `SpringAiLlmGateway` 契约测试 | 测试 | 6 |
| 8 | `TaskController` 加 `@ExceptionHandler` + `ErrorResponse` | 2 文件 | 3 |
| 9 | 空草稿 → `DECOMPOSING_FAILED` 逻辑 | `TaskDecompositionService` | 6 |
| 10 | smoke 集成测试 | 测试 | 4,6 |

Task 1 后必须跑现有 19 个测试,确认 Spring Boot 升级无回归。

## 9. 边界

- **GitNexus 索引未建**:query 返回空 → LLM 输出空数组 → `DECOMPOSING_FAILED`。符合"完全基于真实数据"。
- **LLM 输出非法 JSON**:Spring AI 反序列化抛异常 → 包成 `LlmException` 抛出 → 事务回滚。
- **MCP session 握手失败**:`McpClient` 初始化时炸 → Spring AI 启动失败 → 应用起不来(管理员修配置)。这是配置错误,不是运行时降级场景。
- **本地 LLM 模型未拉**:Ollama 返回 404 → Spring AI 抛 → `LlmException` → 503。
