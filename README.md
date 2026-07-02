# AI Factory

> Turn a natural-language product requirement into a DAG of executable dev tasks — each carrying a fully-assembled prompt for an AI worker (or human) to implement.

AI Factory is a Spring Boot service that orchestrates an AI-driven task decomposition pipeline. It integrates with [GitNexus](https://github.com/) (a code-knowledge-graph service over MCP) for symbol-level code context, and an OpenAI-compatible LLM (e.g., DashScope/Qwen) for decomposition, clarification, and code generation.

## How It Works

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  User Input  │────▶│  GitNexus     │────▶│  LLM Split  │────▶│  DAG Steps   │
│  (requirement)│     │  (code scan)  │     │  (tasks)    │     │  (with deps)  │
└─────────────┘     └──────────────┘     └─────────────┘     └──────┬───────┘
                                                                        │
                    ┌───────────────────────────────────────────────────┘
                    ▼
              ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
              │  Context      │────▶│  Prompt      │────▶│  AI Worker    │
              │  Aggregation  │     │  Assembly    │     │  (claim→code  │
              │  (per step)   │     │  (8 sections)│     │   →complete)  │
              └──────────────┘     └──────────────┘     └──────────────┘
```

### Two Decomposition Modes

**Mode A — Direct Decomposition** (`POST /tasks/decompose`): One-shot — submit requirement → get task DAG.

**Mode B — Conversational Clarification** (`POST /chat/sessions`): Interactive multi-round refinement before decomposition. The LLM asks clarifying questions grounded in real code symbols from GitNexus, then synthesizes a refined requirement for decomposition.

### The 7-Step Decomposition Pipeline

1. **Create parent task** — Insert `Task(requirement, adminId)`
2. **GitNexus query** — Find related symbols + execution flows (the "footprint")
3. **LLM split** — Decompose requirement into `TaskDraft[]` (targetSymbol must come from footprint)
4. **Derive dependencies** — For each step, call `impact()` to find upstream dependents → build DAG edges with cycle detection
5. **Aggregate context** — For each step, pull `context()` (source code, callers) + `impact()` (blast radius)
6. **Assemble prompt** — Build an 8-section Markdown prompt (target symbol, current source, callers, blast radius, design detail, instruction, constraints)
7. **Set initial state** — `dependsOnCount=0` → `READY`; else `PENDING`. Parent → `READY` or `PARTIAL`

### Step State Machine

```
PENDING ──(deps→0)──▶ READY ──(claim)──▶ IN_PROGRESS ──(complete)──▶ DONE
                       ▲                      │
                       │   (fail/revert)      │
                       └──────────────────────┘
```

### AI Worker Execution

`POST /tasks/{id}/execute` runs the full-auto closed loop:

1. **Atomic claim** — CAS `UPDATE ... WHERE status='READY'` (optimistic lock)
2. **LLM code generation** — `executeStep(generatedPrompt)` → pure source code
3. **Write file** — Write to `repoBasePath/repo/targetFile`
4. **Complete** — `detectChanges()` verifies disk changes → mark `DONE` → unlock successors with re-aggregated context
5. **Failure rollback** — `detectChanges` fails → `revertClaim()` → step back to `READY` (retryable)

## Why AI Factory

Unlike generic "LLM → code" tools, AI Factory grounds every decision in **real code structure** and orchestrates a **multi-agent pipeline** rather than a single prompt:

- **Code knowledge graph grounding** — Task target symbols, dependency edges, and prompt context all come from GitNexus's symbol-level code analysis. No LLM hallucination — the LLM can only pick symbols that actually exist in your codebase.
- **Conversational requirement clarification** — Not a one-shot "submit → pray" button. The LLM asks focused clarifying questions grounded in real code symbols (e.g., *"I see `BinaryLogClient` already has `keepAlive`, should the heartbeat reuse it?"*) before decomposing.
- **Automatic DAG derivation** — Step dependencies are auto-derived from code impact analysis, not manually specified. If step A's symbol is called by step B's symbol, the DAG edge is created automatically — with cycle detection.
- **Context re-aggregation** — When a predecessor step completes, successors automatically get fresh `context()` + `impact()` calls. The prompt reflects the *current* state of the codebase, not a stale snapshot from decomposition time.
- **Full-auto AI worker closed loop** — Claim → LLM generates code → write to file → verify disk changes → mark DONE → unlock successors. Zero human intervention needed for the happy path.
- **No-degradation architecture** — Upstream failures (GitNexus, LLM) throw → transaction rollback → HTTP 503. No silent empty data, no fallback paths, no half-baked results in the database.
- **Multi-worker collaboration** — CAS-based atomic claiming prevents double-claiming across concurrent AI instances. `GET /tasks/steps/claimed?userId=` lets workers resume their tasks across sessions — natural support for multi-agent parallel development.
- **Cost-efficient model flexibility** — Because each step carries a fully-assembled prompt (with source code, callers, design detail — all pre-fetched by GitNexus), the LLM only needs to *write code*, not *understand the codebase*. This means you can use cheap or free local models (e.g., Qwen-7B, Llama 3, Ollama) for step execution, while reserving expensive models for the decomposition and clarification stages where reasoning matters most. One premium model call for decomposition, N cheap model calls for execution.

## Key Features

- **DAG-based task decomposition** — Dependencies derived from real code structure (GitNexus impact analysis), not LLM guesses
- **Conversational requirement clarification** — Multi-round LLM interaction before decomposition, grounded in real code symbols
- **Fully-assembled prompts** — Each step carries an 8-section Markdown prompt with source code, callers, blast radius, and design detail
- **Context re-aggregation** — When a predecessor completes, successors get fresh context (code changed → callers/impact may shift)
- **Atomic task claiming** — CAS-based `UPDATE...WHERE status='READY'` prevents double-claiming across workers
- **Failure rollback** — `detectChanges` failure → step reverts to `READY` (retryable); LLM failure → transaction rollback
- **Multi-worker support** — `GET /tasks/steps/claimed?userId=X` lets workers resume their tasks across sessions
- **"No graceful degradation"** — All upstream failures throw → HTTP 503 (no silent empty data, no fallback paths)
- **Frontend UI** — Pure HTML+JS dashboard with task list, DAG visualization, step details, and chat interface

## Tech Stack

| Component | Technology |
|---|---|
| Framework | Spring Boot 3.4.5 / Java 17 |
| AI | Spring AI 1.0.0 (OpenAI-compatible, e.g., DashScope/Qwen) |
| Code Graph | GitNexus over MCP (Streamable HTTP transport) |
| ORM | MyBatis-Plus 3.5.9 (pagination, optimistic lock) |
| Database | MySQL 8 + Flyway migrations |
| Testing | JUnit 5 + Mockito + H2 (in-memory) |
| Frontend | Pure HTML + JavaScript (no framework) |

## Quick Start

### Prerequisites

- Java 17+
- MySQL 8+ (or use Docker)
- A running [GitNexus](https://github.com/) instance with your codebase indexed
- An OpenAI-compatible LLM API (e.g., DashScope, OpenAI, Ollama)

### Build & Run

```bash
git clone https://github.com/<your-org>/ai-factory.git
cd ai-factory

# Set environment variables
export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export LLM_API_KEY=your-api-key
export LLM_MODEL=qwen-plus
export GITNEXUS_MCP_URL=http://[::1]:4747/api/mcp
export REPO_BASE_PATH=./repos

# Create database
mysql -u root -p -e "CREATE DATABASE ai_factory"

# Run
mvn spring-boot:run --server.port=8082
```

Open http://localhost:8082 in your browser.

### Configuration

All config via `application.yml` or environment variables:

| Property | Default | Description |
|---|---|---|
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/ai_factory` | MySQL connection |
| `spring.ai.openai.base-url` | `${LLM_BASE_URL}` | OpenAI-compatible API base URL |
| `spring.ai.openai.api-key` | `${LLM_API_KEY}` | API key |
| `spring.ai.openai.chat.options.model` | `${LLM_MODEL}` | Model name |
| `spring.ai.openai.chat.options.temperature` | `0.2` | Low temperature for deterministic output |
| `gitnexus.mcp.url` | `${GITNEXUS_MCP_URL:http://[::1]:4747/api/mcp}` | GitNexus MCP endpoint |
| `factory.worker.repo-base-path` | `${REPO_BASE_PATH:./repos}` | Where AI worker writes generated code |
| `factory.clients.real.enabled` | `true` | Gate for real GitNexus/LLM beans (false = test stubs) |

## REST API

### Task Endpoints (`/tasks`)

| Method | Path | Description |
|---|---|---|
| POST | `/tasks/decompose` | Decompose requirement into task DAG |
| POST | `/tasks/{id}/claim` | Worker claims a READY step (returns prompt) |
| POST | `/tasks/{id}/complete` | Worker completes a step (triggers successor unlock) |
| POST | `/tasks/{id}/execute` | Full-auto: claim → LLM → write file → complete |
| GET | `/tasks` | List tasks (paginated, filterable by status) |
| GET | `/tasks/{id}` | Get task detail |
| GET | `/tasks/{id}/steps` | List steps (paginated) |
| GET | `/tasks/{id}/dependencies` | List DAG edges |
| GET | `/tasks/steps/claimed?userId=` | List worker's claimed (IN_PROGRESS) steps |
| GET | `/tasks/steps/{stepId}` | Get full step detail (incl. generatedPrompt) |

### Chat Endpoints (`/chat/sessions`)

| Method | Path | Description |
|---|---|---|
| POST | `/chat/sessions` | Start clarification session (GitNexus scan + first question) |
| POST | `/chat/sessions/{id}/messages` | Send answer, get next question or `ready=true` |
| POST | `/chat/sessions/{id}/decompose` | Confirm decomposition with refined requirement |

**Error handling**: `GitNexusException` / `LlmException` → HTTP 503 `{"code":"UPSTREAM_UNAVAILABLE","message":"..."}`. Session not found → HTTP 404.

## Project Structure

```
src/main/java/com/factory/ai/
├── FactoryApplication.java
├── config/
│   └── MyBatisPlusConfig.java               # Pagination + optimistic lock interceptors
├── gitnexus/                                 # GitNexus MCP integration
│   ├── GitNexusClient.java                   # Interface: query / context / impact / detectChanges
│   ├── SpringAiMcpGitNexusClient.java        # Real impl via MCP callTool
│   ├── GitNexusMcpClientConfiguration.java   # McpSyncClient bean
│   ├── StreamableHttpClientTransport.java    # Custom MCP transport (POST → SSE response)
│   └── dto/                                  # SymbolRef, QueryResult, SymbolContext, ImpactResult
├── task/
│   ├── domain/                               # Task, TaskStep, TaskDependency, enums
│   ├── mapper/                               # MyBatis-Plus mappers (claimTask, revertClaim, etc.)
│   ├── service/
│   │   ├── TaskDecompositionService.java     # 7-step orchestration (core)
│   │   ├── DependencyDerivationService.java  # DAG derivation + cycle detection
│   │   ├── ContextAggregationService.java    # Per-step context + re-aggregation
│   │   ├── PromptAssemblyService.java        # 8-section Markdown prompt builder
│   │   ├── TaskClaimService.java             # Atomic CAS claim
│   │   ├── TaskCompletionService.java        # DONE → unlock successors → auto-complete parent
│   │   ├── TaskExecutionService.java         # Full-auto: claim → LLM → write → complete
│   │   ├── LlmGateway.java                   # Interface: splitTasks / executeStep / clarify
│   │   ├── SpringAiLlmGateway.java           # Spring AI ChatClient impl
│   │   └── LlmPromptBuilder.java             # System prompts + user message templates
│   └── web/
│       ├── TaskController.java               # /tasks REST endpoints
│       └── dto/                              # Request/response DTOs + PageResponse
└── chat/                                     # Conversational requirement clarification
    ├── service/
    │   └── ChatClarificationService.java     # start / sendMessage / decompose
    ├── session/                              # In-memory session store (30-min TTL)
    │   ├── ChatSession.java
    │   ├── ChatMessage.java
    │   ├── ChatSessionStore.java
    │   └── InMemoryChatSessionStore.java
    └── web/
        ├── ChatController.java               # /chat/sessions REST endpoints
        └── dto/
```

## Database Schema

Three tables managed by Flyway:

- **`task`** — Parent requirement (id, requirement, status, created_by, created_at)
- **`task_step`** — Sub-task with state machine (status, assignee_id, depends_on_count, version, generated_prompt, design_detail, context_snapshot, reaggregated_at)
- **`task_dependency`** — DAG edge (from_step_id → to_step_id)

## Testing

```bash
mvn test
```

Tests use H2 in-memory database and stub GitNexus/LLM beans (via `@ConditionalOnProperty`). 61 tests covering:

- Decomposition pipeline (DAG, cycle detection, empty drafts)
- Claim/complete state transitions
- Context re-aggregation on completion
- AI worker execution with failure rollback
- Chat clarification flow
- REST API (MockMvc)
- End-to-end VIP scenario

## Design Principles

**No graceful degradation** — Upstream failures (GitNexus, LLM) throw unchecked exceptions → transaction rollback → HTTP 503. No silent empty data.

**Transactional integrity** — All decomposition, claim, complete, and execute flows are `@Transactional`; any failure rolls back to a consistent state.

**Optimistic concurrency** — `task_step.version` + conditional `claimTask` UPDATE prevents double-claiming across concurrent workers.

**Real code grounding** — Target symbols, dependencies, and prompts are derived from real code structure via GitNexus, not LLM hallucination.

**Context re-aggregation** — When a predecessor completes, successors get fresh `context()` + `impact()` calls before becoming READY (predecessor's code changed → callers/blast-radius may have shifted).

## For AI Workers

If you're a Claude Code (or other AI) instance looking to pick up tasks, see [WORKER_GUIDE.md](WORKER_GUIDE.md) for the claim → develop → complete workflow.

## License

MIT
