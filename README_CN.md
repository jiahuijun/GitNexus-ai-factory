# AI Factory

> 将自然语言产品需求转化为可执行开发任务的 DAG（有向无环图）—— 每个任务步骤都附带完整组装的提示词，供 AI Worker（或人类）直接实施。

AI Factory 是一个 Spring Boot 服务，编排 AI 驱动的任务拆解流水线。它通过 MCP 协议集成 [GitNexus](https://github.com/) 代码知识图谱获取符号级代码上下文，并调用 OpenAI 兼容的 LLM（如 DashScope/通义千问）完成需求拆解、对话澄清和代码生成。

## 工作原理

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  用户输入     │────▶│  GitNexus     │────▶│  LLM 拆解   │────▶│  DAG 任务图   │
│  (需求文本)   │     │  (代码摸底)   │     │  (任务草稿)  │     │  (含依赖关系)  │
└─────────────┘     └──────────────┘     └─────────────┘     └──────┬───────┘
                                                                        │
                    ┌───────────────────────────────────────────────────┘
                    ▼
              ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
              │  上下文聚合   │────▶│  提示词组装   │────▶│  AI Worker    │
              │  (逐步骤)     │     │  (8 段 Markdown)│   │  (认领→编码   │
              │              │     │              │     │   →完成)      │
              └──────────────┘     └──────────────┘     └──────────────┘
```

### 两种拆解模式

**模式 A — 直接拆解**（`POST /tasks/decompose`）：一次性提交需求 → 生成任务 DAG。

**模式 B — 对话式澄清**（`POST /chat/sessions`）：在拆解前进行多轮交互式需求澄清。LLM 基于 GitNexus 摸底结果中的真实代码符号提出澄清问题，用户回答后合成精炼需求，再执行拆解。

### 7 步拆解流水线

1. **创建父任务** — 插入 `Task(requirement, adminId)`
2. **GitNexus 摸底** — 查找相关符号 + 执行流（"足迹"）
3. **LLM 拆解** — 将需求分解为 `TaskDraft[]`（targetSymbol 必须来自摸底结果，不可凭空发明）
4. **派生依赖** — 对每个步骤调用 `impact()` 获取上游依赖方 → 构建 DAG 边，含环检测
5. **聚合上下文** — 对每个步骤拉取 `context()`（源码、调用方）+ `impact()`（影响面）
6. **组装提示词** — 构建 8 段 Markdown 提示词（目标符号、当前源码、调用方、影响面、设计详情、指令、约束）
7. **设置初始状态** — `dependsOnCount=0` → `READY`；否则 `PENDING`。父任务 → `READY` 或 `PARTIAL`

### 步骤状态机

```
PENDING ──(依赖→0)──▶ READY ──(认领)──▶ IN_PROGRESS ──(完成)──▶ DONE
                       ▲                      │
                       │    (失败/回退)        │
                       └──────────────────────┘
```

### AI Worker 执行流程

`POST /tasks/{id}/execute` 运行全自动闭环：

1. **原子认领** — CAS `UPDATE ... WHERE status='READY'`（乐观锁）
2. **LLM 代码生成** — `executeStep(generatedPrompt)` → 纯源代码
3. **写入文件** — 写入 `repoBasePath/repo/targetFile`
4. **完成** — `detectChanges()` 验证磁盘变更 → 标记 `DONE` → 解锁后继步骤并重聚合上下文
5. **失败回退** — `detectChanges` 失败 → `revertClaim()` → 步骤回退为 `READY`（可重试）

## 为什么选择 AI Factory

与普通的"LLM → 代码"工具不同，AI Factory 将每一步决策都锚定在**真实代码结构**上，并编排**多阶段流水线**而非单一提示词：

- **代码知识图谱接地** — 任务目标符号、依赖边、提示词上下文全部来自 GitNexus 的符号级代码分析。不存在 LLM 幻觉 —— LLM 只能从代码库中真实存在的符号中进行选择。
- **对话式需求澄清** — 不是一键"提交 → 祈祷"按钮。LLM 基于真实代码符号提出聚焦问题（如*"我看到 `BinaryLogClient` 已有 `keepAlive` 机制，心跳检测要复用还是独立？"*），澄清后再拆解。
- **自动 DAG 派生** — 步骤依赖从代码 impact 分析自动推导，无需人工指定。如果步骤 A 的符号被步骤 B 的符号调用，DAG 边自动创建 —— 含环检测。
- **上下文重聚合** — 前驱步骤完成后，后继步骤自动重新调用 `context()` + `impact()`。提示词反映代码库的*当前*状态，而非拆解时的过期快照。
- **全自动 AI Worker 闭环** — 认领 → LLM 生成代码 → 写入文件 → 验证磁盘变更 → 标记 DONE → 解锁后继。正常路径零人工介入。
- **不降级架构** — 上游失败（GitNexus、LLM）直接抛异常 → 事务回滚 → HTTP 503。不返回空数据，不走降级路径，数据库中不残留半成品。
- **多 Worker 协作** — 基于 CAS 的原子认领防止多个 AI 实例并发重复认领。`GET /tasks/steps/claimed?userId=` 让 Worker 跨会话恢复任务 —— 天然支持多 Agent 并行开发。
- **成本可控的模型灵活性** — 每个步骤携带完整组装的提示词（源码、调用方、设计详情全部由 GitNexus 预先获取），LLM 只需*写代码*，不需要*理解代码库*。这意味着步骤执行可以使用免费或廉价的本地模型（如 Qwen-7B、Llama 3、Ollama），而将昂贵模型保留给拆解和澄清阶段。一次高端模型调用做拆解，N 次廉价模型调用做执行。

## 核心特性

- **基于 DAG 的任务拆解** — 依赖关系来自真实代码结构（GitNexus impact 分析），而非 LLM 猜测
- **对话式需求澄清** — 拆解前多轮 LLM 交互，基于真实代码符号提出聚焦问题
- **完整组装的提示词** — 每个步骤携带 8 段 Markdown 提示词，包含源码、调用方、影响面和设计详情
- **上下文重聚合** — 前驱步骤完成后，后继步骤获取最新上下文（代码已变 → 调用方/影响面可能变化）
- **原子任务认领** — 基于 CAS 的 `UPDATE...WHERE status='READY'` 防止多 Worker 重复认领
- **失败回退** — `detectChanges` 失败 → 步骤回退为 `READY`（可重试）；LLM 失败 → 事务回滚
- **多 Worker 支持** — `GET /tasks/steps/claimed?userId=X` 让 Worker 跨会话恢复已认领的任务
- **"不降级"策略** — 所有上游失败抛出异常 → HTTP 503（不返回空数据，不降级处理）
- **前端界面** — 纯 HTML+JS 仪表盘，包含任务列表、DAG 可视化、步骤详情和对话界面

## 技术栈

| 组件 | 技术 |
|---|---|
| 框架 | Spring Boot 3.4.5 / Java 17 |
| AI | Spring AI 1.0.0（OpenAI 兼容，如 DashScope/通义千问） |
| 代码图谱 | GitNexus over MCP（Streamable HTTP 传输） |
| ORM | MyBatis-Plus 3.5.9（分页、乐观锁） |
| 数据库 | MySQL 8 + Flyway 迁移 |
| 测试 | JUnit 5 + Mockito + H2（内存数据库） |
| 前端 | 纯 HTML + JavaScript（无框架） |

## 快速开始

### 前置条件

- Java 17+
- MySQL 8+（或使用 Docker）
- 一个已索引你代码库的 [GitNexus](https://github.com/) 实例
- 一个 OpenAI 兼容的 LLM API（如 DashScope、OpenAI、Ollama）

### 构建与运行

```bash
git clone https://github.com/<your-org>/ai-factory.git
cd ai-factory

# 设置环境变量
export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export LLM_API_KEY=你的API密钥
export LLM_MODEL=qwen-plus
export GITNEXUS_MCP_URL=http://[::1]:4747/api/mcp
export REPO_BASE_PATH=./repos

# 创建数据库
mysql -u root -p -e "CREATE DATABASE ai_factory"

# 运行
mvn spring-boot:run --server.port=8082
```

浏览器访问 http://localhost:8082

### 配置项

通过 `application.yml` 或环境变量配置：

| 属性 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/ai_factory` | MySQL 连接地址 |
| `spring.ai.openai.base-url` | `${LLM_BASE_URL}` | OpenAI 兼容 API 基础 URL |
| `spring.ai.openai.api-key` | `${LLM_API_KEY}` | API 密钥 |
| `spring.ai.openai.chat.options.model` | `${LLM_MODEL}` | 模型名称 |
| `spring.ai.openai.chat.options.temperature` | `0.2` | 低温度，确保输出确定性 |
| `gitnexus.mcp.url` | `${GITNEXUS_MCP_URL:http://[::1]:4747/api/mcp}` | GitNexus MCP 端点 |
| `factory.worker.repo-base-path` | `${REPO_BASE_PATH:./repos}` | AI Worker 写入代码的路径 |
| `factory.clients.real.enabled` | `true` | 真实 GitNexus/LLM Bean 开关（false = 测试桩） |

## REST API

### 任务端点（`/tasks`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/tasks/decompose` | 将需求拆解为任务 DAG |
| POST | `/tasks/{id}/claim` | Worker 认领一个 READY 步骤（返回提示词） |
| POST | `/tasks/{id}/complete` | Worker 完成步骤（触发后继解锁） |
| POST | `/tasks/{id}/execute` | 全自动执行：认领 → LLM → 写文件 → 完成 |
| GET | `/tasks` | 任务列表（分页，可按状态筛选） |
| GET | `/tasks/{id}` | 获取任务详情 |
| GET | `/tasks/{id}/steps` | 步骤列表（分页） |
| GET | `/tasks/{id}/dependencies` | DAG 依赖边列表 |
| GET | `/tasks/steps/claimed?userId=` | 查询 Worker 已认领（IN_PROGRESS）的步骤 |
| GET | `/tasks/steps/{stepId}` | 获取步骤完整详情（含 generatedPrompt） |

### 对话端点（`/chat/sessions`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/sessions` | 开始澄清会话（GitNexus 摸底 + 第一个问题） |
| POST | `/chat/sessions/{id}/messages` | 发送回答，获取下一个问题或 `ready=true` |
| POST | `/chat/sessions/{id}/decompose` | 确认拆解，用精炼需求生成任务 |

**错误处理**：`GitNexusException` / `LlmException` → HTTP 503 `{"code":"UPSTREAM_UNAVAILABLE","message":"..."}`。会话不存在 → HTTP 404。

## 项目结构

```
src/main/java/com/factory/ai/
├── FactoryApplication.java
├── config/
│   └── MyBatisPlusConfig.java               # 分页 + 乐观锁拦截器
├── gitnexus/                                 # GitNexus MCP 集成层
│   ├── GitNexusClient.java                   # 接口：query / context / impact / detectChanges
│   ├── SpringAiMcpGitNexusClient.java        # 基于 MCP callTool 的实现
│   ├── GitNexusMcpClientConfiguration.java   # McpSyncClient Bean
│   ├── StreamableHttpClientTransport.java    # 自定义 MCP 传输（POST → SSE 响应）
│   └── dto/                                  # SymbolRef, QueryResult, SymbolContext, ImpactResult
├── task/
│   ├── domain/                               # Task, TaskStep, TaskDependency, 枚举
│   ├── mapper/                               # MyBatis-Plus Mapper（claimTask, revertClaim 等）
│   ├── service/
│   │   ├── TaskDecompositionService.java     # 7 步编排（核心）
│   │   ├── DependencyDerivationService.java  # DAG 派生 + 环检测
│   │   ├── ContextAggregationService.java    # 逐步骤上下文 + 重聚合
│   │   ├── PromptAssemblyService.java        # 8 段 Markdown 提示词构建
│   │   ├── TaskClaimService.java             # 原子 CAS 认领
│   │   ├── TaskCompletionService.java        # DONE → 解锁后继 → 自动完成父任务
│   │   ├── TaskExecutionService.java         # 全自动：认领 → LLM → 写文件 → 完成
│   │   ├── LlmGateway.java                   # 接口：splitTasks / executeStep / clarify
│   │   ├── SpringAiLlmGateway.java           # Spring AI ChatClient 实现
│   │   └── LlmPromptBuilder.java             # 系统提示词 + 用户消息模板
│   └── web/
│       ├── TaskController.java               # /tasks REST 端点
│       └── dto/                              # 请求/响应 DTO + PageResponse
└── chat/                                     # 对话式需求澄清
    ├── service/
    │   └── ChatClarificationService.java     # start / sendMessage / decompose
    ├── session/                              # 内存会话存储（30 分钟 TTL）
    │   ├── ChatSession.java
    │   ├── ChatMessage.java
    │   ├── ChatSessionStore.java
    │   └── InMemoryChatSessionStore.java
    └── web/
        ├── ChatController.java               # /chat/sessions REST 端点
        └── dto/
```

## 数据库表结构

通过 Flyway 管理的三张表：

- **`task`** — 父任务（id, requirement, status, created_by, created_at）
- **`task_step`** — 子步骤，含状态机（status, assignee_id, depends_on_count, version, generated_prompt, design_detail, context_snapshot, reaggregated_at）
- **`task_dependency`** — DAG 边（from_step_id → to_step_id）

## 测试

```bash
mvn test
```

测试使用 H2 内存数据库和桩 GitNexus/LLM Bean（通过 `@ConditionalOnProperty`）。共 61 个测试，覆盖：

- 拆解流水线（DAG、环检测、空草稿）
- 认领/完成状态转换
- 完成时上下文重聚合
- AI Worker 执行与失败回退
- 对话澄清流程
- REST API（MockMvc）
- 端到端 VIP 场景

## 设计原则

**不降级** — 上游失败（GitNexus、LLM）抛出非受检异常 → 事务回滚 → HTTP 503。不返回空数据。

**事务完整性** — 所有拆解、认领、完成、执行流程均为 `@Transactional`；任何失败回滚到一致状态。

**乐观并发** — `task_step.version` + 条件 `claimTask` UPDATE 防止多 Worker 并发重复认领。

**真实代码接地** — 目标符号、依赖关系、提示词均来自 GitNexus 的真实代码结构，而非 LLM 幻觉。

**上下文重聚合** — 前驱步骤完成后，后继步骤在变为 READY 前重新调用 `context()` + `impact()`（前驱代码已变 → 调用方/影响面可能偏移）。

## AI Worker 指南

如果你是 Claude Code（或其他 AI）实例，想要认领任务，请参阅 [WORKER_GUIDE.md](WORKER_GUIDE.md) 了解认领 → 开发 → 完成的工作流程。

## 开源协议

MIT
