# AI Factory Worker 使用说明

> 给其他 Claude Code 实例：按以下步骤认领并执行 AI Factory 中的任务。
> API 地址：http://localhost:8082
> 仓库路径：D:/code/mysql-connector-java/mysql-binlog-connector-java

---

## 1. 查看我的待办（已认领未完成的步骤）

```bash
curl -s "http://localhost:8082/tasks/steps/claimed?userId=1" | python -m json.tool
```

返回的每个步骤包含 `generatedPrompt` 字段——这是完整的开发指令，直接按它编码。

## 2. 没有待办？认领新任务

先找到 READY 状态的步骤：

```bash
# 列出所有任务
curl -s "http://localhost:8082/tasks" | python -m json.tool

# 查看某个任务的步骤（找 status=READY, dependsOnCount=0 的）
curl -s "http://localhost:8082/tasks/5/steps" | python -m json.tool
```

认领：

```bash
curl -s -X POST "http://localhost:8082/tasks/9/claim" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1}' | python -m json.tool
```

- 返回 JSON（含 `generatedPrompt`）= 认领成功
- 返回空 + HTTP 409 = 已被抢或非 READY

## 3. 按 prompt 开发

返回的 `generatedPrompt` 包含：
- **Target Symbol**：要修改的类/方法
- **Current Source**：当前源码
- **Callers**：谁调用了这个符号（不能破坏）
- **Design Detail**：详细设计方案
- **Instruction**：编码指令

用 Read 读取 `targetFile`，用 Edit 按 Design Detail 修改代码。

## 4. 完成步骤

```bash
curl -s -X POST "http://localhost:8082/tasks/9/complete" \
  -H "Content-Type: application/json" \
  -d '{"userId": 1, "repo": "mysql-binlog-connector-java"}'
```

- 返回 `true` = 成功（代码变更检测通过，步骤标记 DONE，后继步骤自动解锁）
- 返回 `false` = 变更检测未通过（步骤自动回退为 READY，可重试）

## 状态流转

```
PENDING → READY → IN_PROGRESS → DONE
                      ↓ (失败)
                    READY (可重试)
```

## 查看步骤完整详情（不需要认领也能看）

```bash
curl -s "http://localhost:8082/tasks/steps/9" | python -m json.tool
```

返回 `task_step` 表所有字段，包括 `generatedPrompt`、`designDetail`、`contextSnapshot`。
