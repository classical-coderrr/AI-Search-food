# 小厨灵企业级 Agent 验收报告

## 目标

小厨灵采用持久化状态、步骤级恢复、写操作幂等和 SSE 事件续传，服务重启或浏览器断线后可以继续任务，并且不会重复执行有副作用的写操作。

## 真实 Docker 演练记录

执行日期：2026-09-21。使用专用演练账号，在当前 Docker Compose 环境中实际操作 MySQL、Redis 和 backend 容器；未删除数据卷。

| 演练 | 结果 | 实际证据 |
| --- | --- | --- |
| 运行中任务恢复 | 通过 | 对 run `6c26de13-873e-4298-b0fc-c83d15e4f461` 执行 backend `SIGKILL`；backend、MySQL、Redis 均重新变为 healthy，恢复实例接管 Redis checkpoint，日志出现 `Recovering agent run`，目标 run 最终为 `COMPLETED`。 |
| 写事务崩溃窗口 | 通过 | 对 confirmation `17` / idempotency key `crash-drill-20260921-01` 在“业务写入后”暂停期间执行 backend `SIGKILL`；重启后 `agent_write_operations.status=PROCESSING`、`agent_confirmations.status=PENDING`，演练食材未落入 `user_pantry_items`。 |

当前结论：写操作未知结果保护和步骤级 Agent 恢复均已完成真实 Docker 验收。恢复租约增加了恢复实例短期存活标记，旧实例在崩溃后不会把残留的 `recovery:` 租约永久阻塞新实例接管。演练现场和数据记录暂保留，便于复现。

## 运行时间线

```text
用户请求
  -> AgentRun / AgentCheckpoint
  -> 模型决策（AgentStep）
  -> 工具开始（AgentStep + checkpoint）
  -> 工具结果（AgentStep + checkpoint）
  -> SSE 事件（runId + event_seq，Redis 持久化）
  -> 完成 / 等待确认 / 人工复核
```

## 故障演练

### 步骤级恢复

使用 `scripts/agent-recovery-drill.ps1`，在模型或工具执行期间 SIGKILL backend。重启后扫描器从 Redis checkpoint 继续，最终状态应为 `COMPLETED` 或 `WAITING_CONFIRMATION`。

### 写事务崩溃窗口

使用 `scripts/agent-write-crash-drill.ps1`，在“业务写入完成、幂等账本完成前”终止 backend。重启后应看到：

```text
agent_write_operations.status = PROCESSING
agent_confirmations.status = PENDING
```

系统不会猜测未知副作用，也不会自动重试写操作。

## SSE 断线恢复

```text
GET /api/agent/runs/{runId}/events?afterEventSeq=3
GET /api/agent/runs/{runId}/events/stream?afterEventSeq=3
Last-Event-ID: 3
```

事件在 Redis 中按 `runId` 单调递增，客户端按事件序号去重。原 SSE 连接断开不再取消后台 Agent，刷新页面后可以补发遗漏事件并加载最终会话历史。

## 观测指标

管理员可通过 Actuator 查看：

```text
agent.runs.started
agent.runs.completed
agent.runs.failed
agent.runs.recovered
agent.runs.duration
agent.events.persisted
agent.events.replayed
agent.writes.duplicate
```

审计步骤只保存路由元数据、长度、来源和结果摘要；`AgentAuditSanitizer` 会对 Token、密码、API Key、手机号、Authorization 等字段脱敏。

## 面试展示重点

1. 至少一次执行 + 幂等键保证写操作安全；
2. Redis checkpoint + 租约避免多实例重复恢复；
3. 事件序号和 Last-Event-ID 让 SSE 支持断线续传；
4. 未知结果进入 `PROCESSING` / 人工复核，而不是盲目重试；
5. 指标、审计、故障注入和自动化测试形成闭环。
