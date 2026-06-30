# GitNexus AI Factory — 跨任务依赖模型设计

> 版本: V6.1 (依赖模型补丁)
> 日期: 2026-06-30
> 状态: 待审阅
> 父文档: GitNexus AI Factory - 预置上下文版 (V6.0)

## 0. 项目概述

### 0.1 项目是什么

**GitNexus AI Factory** 是一个基于 GitNexus 代码上下文理解能力的 AI 任务分发系统。它把一个完整的产品需求，拆解成若干个可独立执行的简单开发任务，每个任务都预置了精确的代码上下文和指令，分发给不同员工（Claude Code 实例）去完成。

核心定位：**从"实时查询系统"变成"任务分发系统"**——重计算（上下文聚合、Prompt 生成）发生在低频的管理员规划阶段，轻查询（领任务）发生在高频的员工执行阶段。

### 0.2 解决什么问题

传统 AI 编码助手在接到需求时，要么缺乏代码上下文导致生成错误代码，要么实时拉取上下文导致响应慢（3-5 秒）。本系统通过：

1. **GitNexus 提供代码理解**：基于已索引的代码知识图谱，提供符号检索、依赖分析、影响面计算、变更检测等能力。
2. **上下文前置**：在管理员拆解任务时就聚合好上下文、组装好 Prompt，冻结到数据库。
3. **任务即取即用**：员工领取任务时只是纯 DB 读，毫秒级返回预置 Prompt。

### 0.3 项目整体流程

系统分两个阶段，覆盖从需求到交付的完整链路：

```
┌─────────────────────────────────────────────────────────┐
│ 阶段一：规划与预置 (管理员触发，低频，可容忍延迟)          │
├─────────────────────────────────────────────────────────┘
 1. 管理员提交需求 ─────────────────────────────┐
 2. GitNexus query() 摸底：相关符号/文件/执行流   │
 3. LLM 基于结构化信息拆解 → 任务草稿(带目标符号) │
 4. 依赖派生：impact(upstream) 自动连边 → DAG     │  本补丁
 5. 环检测：有环则合并任务，标 needs_review       │  重点
 6. 初始上下文聚合：context() + impact() → prompt │
 7. 存 MySQL：task / task_step / task_dependency  │
 8. 管理员 Web 预览/微调 prompt，确认分发         │
                                                 │
┌─────────────────────────────────────────────────┘──────┐
│ 阶段二：执行与开发 (员工触发，高频，要求极速)            │
├─────────────────────────────────────────────────────────┘
 9.  员工领取任务 (READY 态) → 原子 UPDATE → IN_PROGRESS
       └ 返回 generated_prompt，毫秒级，纯 DB 读
 10. Claude Code 按 prompt 直接改文件、跑测试
 11. 员工调 complete_task
       ├ detect_changes() 验证触及预期符号 (防假完成)
       ├ 标记 DONE
       └ 同步重聚合后继任务上下文 → 后继解锁为 READY  ← 本补丁重点
 12. 后继任务回到步骤 9，循环直到 DAG 全部 DONE
 13. 管理员在 Web 查看整体进度，所有 step DONE → task DONE
```

**两阶段的分工**：阶段一重计算（GitNexus 多次调用 + LLM 拆解），阶段二轻查询（纯 DB 读）。独立任务在阶段二零 GitNexus 调用；有依赖的任务在解锁时触发一次同步重聚合。

### 0.4 GitNexus 在流程中的角色

| 流程节点 | GitNexus 工具 | 作用 |
|---|---|---|
| 摸底（步骤 2） | `query()` | 拿相关符号+文件路径+执行流，喂给 LLM |
| 依赖派生（步骤 4） | `impact(target, "upstream")` | 自动连依赖边，不信任 LLM 自报 |
| 初始聚合（步骤 6） | `context(name, include_content)` + `impact()` | 取源码+调用方+影响面，组装 prompt |
| 完成验证（步骤 11） | `detect_changes()` | 确认员工真改了预期符号 |
| 重聚合（步骤 11） | `context()` + `impact()` | 后继任务拿到含前序改动的新鲜上下文 |

## 1. 背景与问题

V6.0 的"冻结上下文"机制（§4.2）将管理员拆解时的代码状态冻结到数据库，员工领取时纯 DB 读、毫秒级返回。该机制对**独立任务**成立，但对**有依赖的任务**是致命缺陷：

- 典型场景：Task A 给 `UserService` 加 `getVipLevel`，Task B 让 `UserController` 调用它。
- V6.0 行为：B 的 `context_snapshot` 是规划时旧 `UserService`，没有 `getVipLevel`。员工按旧上下文写 Controller，编译必挂。
- V6.0 未定义：任务排序、依赖声明、前序完成后是否刷新后序上下文、完成验证闭环。

本补丁定义跨任务依赖模型，修复上述缺陷，同时保留 V6.0 对独立任务的性能优势。

## 2. 关键决策（已锁定）

| 决策点 | 选定方案 | 理由 |
|---|---|---|
| 需求形态 | 混合（单文件/多文件/跨模块） | 系统须自适应，不能一刀切 |
| 执行时序 | 依赖链顺序：前序 DONE 才解锁后序 | correctness-friendly，与 GitNexus `detect_changes` 天然契合 |
| 依赖来源 | GitNexus 图自动派生（不信任 LLM 自报） | LLM 看不到真实调用图，图查询可靠 |
| 派生粒度 | 符号级（基于 `impact` 上游） | 最准、并行度最高、数据源已实测验证 |
| 状态机 | 四态：PENDING / READY / IN_PROGRESS / DONE (+CANCELLED) | 减少复杂度，ASSIGNED 合并入 IN_PROGRESS |
| 重聚合时机 | 同步（complete_task 内阻塞跑） | 就一次 context+impact，实测秒内 |
| 上下文字段类型 | mediumtext (16MB) | 单符号源码+摘要极少超限，context() 本身会截断 |

## 3. 依赖图模型

### 3.1 数据结构

任务依赖是一张 **DAG**（有向无环图）。

- 每个节点是一个 `task_step`。
- 依赖边存独立表 `task_dependency(from_step_id, to_step_id)`，语义"`to` 依赖 `from`"（`from` 完成后 `to` 才解锁）。
- `task_step.depends_on_count` 冗余字段 = 未完成的前序数，降到 0 即可领——避免每次领任务都查图。

### 3.2 派生算法（拆解后立刻跑一遍）

LLM 拆出 N 个任务草稿，每个带 `target_symbol`（符号名，非文件路径）。然后：

```
对每个任务 A（目标符号 S_A）:
    upstream = impact({target: S_A, direction: "upstream", maxDepth: 1}).byDepth["1"]
    # upstream = 直接依赖 S_A 的符号集合（A 的调用方/读者）
    对每个其他任务 B（目标符号 S_B）:
        if S_B ∈ upstream  或  S_B 的所属类 ∈ upstream:
            连边 A → B   # B 用了 A 改的东西，B 依赖 A
            B.depends_on_count++
```

**方向性依据**：A 改 `S_A`，谁受影响 = 谁依赖 `S_A` = `impact(S_A, "upstream")`。B 若在其中，说明 B 的代码路径经过 A 改的符号——B 必须等 A 改完才能拿到正确上下文。

**实测验证**：`impact(BinaryLogClient, "upstream")` 返回 `byDepth["1"]` = 直接 import/调用它的符号集合，正是受影响方。数据可得且语义正确。

### 3.3 环检测与处理

派生完跑拓扑排序。若出现环（A↔B 互相依赖，现实少见但可能）：

- **自动策略**：环上任务合并成一个 `task_step`（一个员工一次改完互相纠缠的符号），打破环。
- **人工兜底**：合并后的任务标 `needs_review`，管理员在 Web 界面确认，可手动拆回或调整。

### 3.4 独立任务（无入边）

`depends_on_count = 0` 的任务，拆解完即可领。这是 V6.0"即拿即用"性能优势保留的常见路径——只有真有依赖的任务才走"等待-解锁-重聚合"流程。两套规则共存，由依赖图自动路由。

## 4. 任务状态机

### 4.1 状态定义

```
PENDING → READY → IN_PROGRESS → DONE
  ↑                          │
  └── CANCELLED ←────────────┘ (管理员取消)
```

| 状态 | 含义 | 可领？ |
|---|---|---|
| `PENDING` | 已拆解、上下文已聚合，但有未完成的前序（`depends_on_count > 0`） | ❌ |
| `READY` | 所有前序 DONE，`depends_on_count = 0`，上下文是新鲜的 | ✅ |
| `IN_PROGRESS` | 员工已原子领取并开工 | — |
| `DONE` | 员工调 `complete_task` 且 `detect_changes` 验证通过 | — |
| `CANCELLED` | 管理员取消 | — |

`PENDING → READY` 的转换不是显式赋值，而是前序完成事件的副作用（见 §5.2）。

### 4.2 原子领取（修原 §3.2 竞态）

`start_task` 改成单条原子 UPDATE，不再两步读-改-存：

```sql
UPDATE task_step
SET status = 'IN_PROGRESS', assignee_id = :userId, version = version + 1
WHERE id = :taskId AND status = 'READY';
```

返回影响行数 = 0 → 被抢或不可领。`version` 字段同时承担乐观锁职责。

## 5. 解锁触发与同步重聚合

### 5.1 完成验证闭环

员工调 `complete_task(taskId)`：

1. `detect_changes()` 验证改动触及预期符号（防"假完成"）。未触及 → 拒绝标记 DONE，回退 IN_PROGRESS。
2. 标记当前任务 DONE。
3. 查 `task_dependency WHERE from_step_id = taskId` → 拿到后继列表。
4. 对每个后继 B（同步）：
   - `B.depends_on_count -= 1`
   - 若 `B.depends_on_count == 0`：
     - 重新聚合 B 的上下文（见 §5.2）
     - `B.status = 'READY'`（解锁）
5. 返回完成回执。

### 5.2 重聚合（关键机制）

```
重新聚合 B:
    ctx = context({name: B.target_symbol, include_content: true})
    imp = impact({target: B.target_symbol, direction: "upstream"})
    B.context_snapshot = summarize(ctx)
    B.generated_prompt = rebuildPrompt(B, ctx, imp)
    B.reaggregated_at = NOW()
```

**为什么必须重聚合**：B 的 `context_snapshot` 最初是规划时基于旧代码聚合的。此时 A 已改完，B 真正要操作的符号源码已变（如 A 给 Service 加了 `getVipLevel`，B 的 Controller 现在能调用到新方法）。不重聚合，B 拿到的 prompt 仍是旧的，编译必挂——这正是 V6.0 §4.2 在有依赖时的致命缺陷，此处修复。

**`detect_changes()` 的双重作用**：(1) 完成验证——确认员工真改了预期符号；(2) 为后继重聚合提供"哪些符号变了"的精确清单，可只重取受影响符号而非整图。

**同步执行**：重聚合就一次 `context()` + `impact()` 调用，实测秒内完成，员工在 `complete_task` 时阻塞可接受。无需引入异步队列的复杂度。

### 5.3 失败回退

员工中途放弃（`abandon_task`）：任务从 IN_PROGRESS 回到 READY，`assignee_id` 清空，**不重聚合**（上下文仍是上次解锁时的快照，依然有效）。其他员工可继续领。

## 6. Prompt 组装

替换原 §3.1 的简陋模板，匹配 Claude Code 的 agent 行为（直接改文件），并把 GitNexus 真实能给的上下文全用上：

```
# Task
{step_name} — 需求: {requirement}

# Target Symbol
符号: {target_symbol}
文件: {file_path}:{startLine}-{endLine}

# Current Source (fresh)
{context.source_content}
# 重聚合时是含前序改动的最新源码；context() 截断时改用方法粒度或 Read 补全

# Callers (谁会调用你改的符号 — 改动要兼容这些调用方)
{context.incoming.calls}

# Blast Radius (改这个符号会影响谁 — 风险提示)
{impact.upstream.byDepth["1"]}

# Execution Flow (若 query 命中执行流则附上;无则省略)
{processes}

# Instruction
在上述文件中实现需求。直接修改文件，不要只输出代码片段。
遵循现有代码风格。完成后跑相关测试。
最后调用 complete_task({task_id}) 提交。

# Constraints
- 不要改未列入 Target 的文件
- 不要破坏 Callers 中列出的调用方契约
```

**与 V6.0 原版的关键差异**：

- "只输出代码块" → "直接修改文件"（匹配 Claude Code agent 行为）
- 新增 Callers 段（`context().incoming`）——员工知道改动不能破坏谁
- 新增 Blast Radius 段（`impact()`）——风险可见
- 显式要求调 `complete_task`——闭环
- 显式约束不改其他文件——防止越界

## 7. Schema 落地

修原 §2 的表，补父表、依赖表、状态枚举、乐观锁：

```sql
-- 父表：一个需求对应一个 task
CREATE TABLE `task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `requirement` text NOT NULL,
  `status` varchar(32) DEFAULT 'DECOMPOSING',
    -- DECOMPOSING / READY / PARTIAL(needs_review) / DONE / CANCELLED
  `created_by` bigint NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `task_step` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint NOT NULL,
  `step_name` varchar(255) NOT NULL,
  `target_symbol` varchar(255) NOT NULL,   -- 符号名(替换原 target_file 作查询入参)
  `target_file` varchar(512),              -- 保留作展示,来自 context() 的 filePath
  `status` varchar(32) DEFAULT 'PENDING',
    -- PENDING / READY / IN_PROGRESS / DONE / CANCELLED
  `assignee_id` bigint DEFAULT NULL,
  `depends_on_count` int DEFAULT 0,        -- 未完成前序数;0 即可领
  `version` int DEFAULT 0,                 -- 乐观锁
  `context_snapshot` mediumtext,           -- context() 摘要(源码+调用方)
  `generated_prompt` mediumtext,           -- 组装好的指令
  `reaggregated_at` datetime DEFAULT NULL, -- 区分初始聚合 vs 重聚合
  `needs_review` tinyint DEFAULT 0,        -- 环合并等需人工确认
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task_status` (`task_id`, `status`, `depends_on_count`),
  CONSTRAINT `fk_step_task` FOREIGN KEY (`task_id`) REFERENCES `task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 依赖边
CREATE TABLE `task_dependency` (
  `from_step_id` bigint NOT NULL,   -- 前序
  `to_step_id` bigint NOT NULL,     -- 后继(依赖前序)
  PRIMARY KEY (`from_step_id`, `to_step_id`),
  CONSTRAINT `fk_dep_from` FOREIGN KEY (`from_step_id`) REFERENCES `task_step`(`id`),
  CONSTRAINT `fk_dep_to` FOREIGN KEY (`to_step_id`) REFERENCES `task_step`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**与 V6.0 §2 的改动**：

- `target_file` → 新增 `target_symbol`（真实查询入参）；`target_file` 保留作展示
- `status` 统一为四态（+CANCELLED）
- 新增 `depends_on_count`（解锁判据）、`version`（乐观锁）、`reaggregated_at`、`needs_review`
- `text` → `mediumtext`
- 补 `task` 父表存原始需求
- 新增 `task_dependency` 依赖边表
- 加 `idx_task_status` 复合索引（领取查询高频）

## 8. 端到端时序示例（VIP 需求）

```
t0  管理员提交"增加VIP等级查询"
t1  query → 命中 UserService, UserController 符号
t2  LLM 基于结构化信息拆出:
      A(改UserService加getVipLevel), B(改UserController调用它)
t3  impact(UserService, upstream) → 含 UserController
    → 连边 A→B, B.depends_on_count=1
t4  A 初始聚合: context(UserService) → 存 prompt, A.status=READY
    B 初始聚合: context(UserController, 旧版) → 存 prompt, B.status=PENDING
t5  管理员预览,微调 B 的 prompt("注意事务"),分发

t6  员工1 领 A (READY → IN_PROGRESS), 毫秒级返回 prompt
t7  员工1 改 UserService, 调 complete_task(A)
t8  detect_changes 确认触及 UserService → A=DONE
t9  同步重聚合 B:
      context(UserController) 现在能看到 UserService.getVipLevel ✅
      B.status = READY
t10 员工2 领 B, 拿到含新 getVipLevel 上下文的 prompt → 编译通过
```

对比 V6.0：若 B 在 t5 就被领走，原设计无 PENDING 态拦不住，员工2 拿到旧 prompt → 编译挂。本设计在 t9 才解锁 B，规避此问题。

## 9. 边界与降级

- **GitNexus 不可达**：拆解阶段失败 → task 标 `DECOMPOSING_FAILED`，管理员可手动建任务。执行阶段不影响（prompt 已存 DB）。
- **context() 截断**：大符号 fallback 到方法粒度查询，或 prompt 里指明"用 Read 工具读 {file_path}:{start}-{end}"。
- **环合并后任务过大**：标 `needs_review`，管理员可手动拆或指定单人完成。
- **无前序但有 query 命中执行流**：processes 作为补充上下文塞进 prompt（方案 C 降级为附加信息，不作连接判据）。

## 10. 闭环验证清单

| 环节 | 机制 | GitNexus 工具 |
|---|---|---|
| 拆解不盲拆 | query 先摸底 | `query()` |
| 依赖准 | 图自动派生 | `impact(upstream)` |
| 完成不造假 | 改动触及预期符号 | `detect_changes()` |
| 后续不拿旧上下文 | 解锁时重聚合 | `context()` + `impact()` |
| 风险可见 | prompt 含调用方+爆炸半径 | `context().incoming` + `impact()` |
| 独立任务零开销 | 无入边,初始即 READY,领取纯DB读 | — |

## 11. 对 V6.0 原文的修订点汇总

| 原文章节 | 修订内容 |
|---|---|
| §2 数据库 | 见本文 §7：补父表/依赖表/四态/乐观锁/符号字段 |
| §3.1 拆解伪代码 | 见本文 §3.2 + §6：先 query 后拆、输出符号名、context() 取代 getFileContent |
| §3.2 领取伪代码 | 见本文 §4.2：原子 UPDATE 取代读-改-存 |
| §3.2 缺 complete_task | 见本文 §5：补完成验证+解锁重聚合闭环 |
| §4.2 "冻结上下文"优势 | 降级为仅对独立任务成立；有依赖时改用重聚合（本文 §5.2） |
| §4 状态枚举不一致 | 统一四态（本文 §4.1） |
