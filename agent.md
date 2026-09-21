# 小厨灵企业级 Agent 实现方案

## 0. 文档目的与维护规则

这份文档是小厨灵 Agent 从“可工作的 Demo”走向“可恢复、可审计、可重试的生产级 Agent”的实施清单。方案完成前不得删除或覆盖本文件。

每完成一个功能，交付时必须明确说明：

1. 本次完成了什么；
2. 通过了哪些测试或演练；
3. 当前还剩哪些未完成项；
4. 下一步推荐做什么，以及为什么。

只有所有验收标准全部通过，并得到用户确认后，才可以从 `agent.md` 删除本方案，或将其改为已完成归档记录。

## 1. 目标

用户发起一次 Agent 请求后，即使发生网络断开、服务进程重启或容器重建，也能：

- 找回原来的会话消息；
- 找到这次 Agent 运行的状态、执行步骤和最后 checkpoint；
- 从最后一个可靠节点继续，而不是从头重复整段对话；
- 对写操作保持用户确认和幂等，避免重复扣库存、重复保存或重复撤销；
- 在 SSE 断线后补发遗漏事件，并继续接收实时事件；
- 对每次运行提供可查询、可审计、可测试的证据。

企业级目标采用“至少一次执行 + 幂等保证”，不假设工具绝对只执行一次。

## 2. 三个逻辑实体

下面是逻辑表结构。当前小厨灵将运行态写入 Redis，消息正文仍写入 MySQL；Redis key 与逻辑表一一对应，后续如需迁移到关系型数据库，保持字段语义不变。

### 2.1 `agent_runs`：一次完整 Agent 任务

记录运行的身份、状态和恢复入口：

```text
run_id
conversation_id
user_id
status
current_node
next_node
round
tool_call_count
last_heartbeat_at
pending_confirmation_id
created_at
updated_at
```

状态约定：

```text
RUNNING
TOOL_EXECUTING
WAITING_CONFIRMATION
RECOVERING
COMPLETED
FAILED
CANCELLED
```

当前代码使用 `current_node=TOOL_EXECUTE` 表示工具执行阶段，状态仍可为 `RUNNING`；如果需要按状态统计，后续增加 `TOOL_EXECUTING` 的显式映射。

### 2.2 `agent_steps`：每一步执行过程

记录模型决策、工具开始、工具结果和错误，便于审计、排障和恢复判断：

```text
run_id
step_no
node_type
tool_name
status
request_json
response_json
idempotency_key
started_at
finished_at
error_message
```

典型步骤：

```text
第 1 步：模型决定调用 pantry_expiry
第 2 步：pantry_expiry 执行成功
第 3 步：模型决定调用 recipe_generate
```

### 2.3 `agent_checkpoints`：最新可恢复状态

保存恢复所需的完整状态快照：

```text
run_id
state_json
last_completed_step
next_node
state_version
created_at
```

`state_json` 至少应能还原：

```json
{
  "userGoal": "用快过期食材做晚餐",
  "nextNode": "DECIDE",
  "round": 2,
  "toolCallCount": 1,
  "observations": [
    {
      "tool": "pantry_expiry",
      "result": ["鸡蛋", "西红柿"]
    }
  ]
}
```

当前 Redis 映射：

```text
agent:run:<runId>
agent:steps:<runId>
agent:checkpoint:<runId>
agent:runs:active
```

## 3. 正常执行时的持久化顺序

以“快过期的食材可以做什么晚餐？”为例：

1. 创建 `agent_run`，状态为 `RUNNING`；
2. 模型决定调用 `pantry_expiry`；
3. 保存模型决策 `agent_step`；
4. 保存 checkpoint，`next_node=TOOL_EXECUTE`；
5. 执行 `pantry_expiry`；
6. 保存工具成功 `agent_step`；
7. 保存 checkpoint，`next_node=DECIDE`，并写入工具结果；
8. 再次调用模型；
9. 模型决定调用 `recipe_generate`；
10. 重复模型决策、工具开始、工具结果和 checkpoint 保存；
11. 模型不再请求工具时，保存最终消息并将运行标记为 `COMPLETED`。

必须在以下边界保存 checkpoint：

```text
模型调用前
模型调用后
工具调用前
工具调用后
等待用户确认时
任务完成时
任务失败时
```

心跳应在长模型调用和长工具调用期间持续更新，避免恢复扫描器误判仍在执行的任务。

## 4. 服务重启恢复流程

Spring Boot 启动后，恢复扫描器按固定间隔查询：

```text
status = RUNNING 或 TOOL_EXECUTING
且 last_heartbeat_at < 当前时间 - stale_after
```

恢复流程：

1. 通过租约抢占任务，防止多个实例同时恢复同一个 `run_id`；
2. 将任务标记为 `RECOVERING`；
3. 读取最新 checkpoint；
4. 根据 `next_node` 选择恢复入口；
5. 从该节点继续执行；
6. 每个节点完成后更新 checkpoint、step、心跳和运行状态；
7. 成功进入 `COMPLETED`，不可恢复或异常进入 `FAILED`。

恢复分发逻辑：

```java
AgentState state = checkpointStore.load(runId);

switch (state.nextNode()) {
    case "DECIDE" -> decide(state);
    case "TOOL_EXECUTE" -> executeTool(state);
    case "OBSERVE" -> observe(state);
    case "WAITING_CONFIRMATION" -> waitForUser(state);
    case "FINALIZE" -> finalizeRun(state);
}
```

包含图片附件的运行默认不自动恢复，应明确标记为不可恢复并提示用户重新提交；等待确认的运行只能恢复为等待状态，不能自动执行写操作。

## 5. 崩溃场景处理

### 5.1 模型决定调用工具后崩溃

checkpoint 已记录 `next_node=TOOL_EXECUTE` 和待执行工具。重启后直接执行该工具，不重新询问模型。

### 5.2 工具完成后崩溃

若已保存工具成功 step 和结果，checkpoint 为 `next_node=DECIDE`，重启后跳过该工具，把已有结果交给模型继续决策。

### 5.3 等待确认时崩溃

状态保持 `WAITING_CONFIRMATION`，恢复后只显示确认卡片，必须由用户再次确认，不能自动执行写操作。

### 5.4 写操作执行到一半崩溃

写工具必须遵循：

1. 生成稳定的 `idempotency_key`；
2. 先保存工具执行记录；
3. 执行写操作；
4. 保存成功或失败结果；
5. 重启后根据幂等键查询执行结果；
6. 无法确认时进入人工复核或明确失败，不盲目重试。

典型确认状态：`PENDING`、`PROCESSING`、`CONFIRMED`、`FAILED`、`UNKNOWN_REVIEW`。

## 6. SSE 断线与续传

SSE 连接本身不能跨服务重启保留，因此必须持久化事件序号和事件内容：

```text
run_id
event_seq
event_type
payload_json
created_at
```

前端断线后携带：

```text
runId=abc123
lastEventId=3
```

后端执行顺序：

1. 查询 `event_seq > 3` 的历史事件；
2. 按序补发历史事件；
3. 再切换到实时事件推送；
4. 前端按 `event_seq` 去重，避免重复渲染。

恢复对象是“任务状态 + 执行结果 + 事件流”，不是原来的 TCP/SSE 连接。

当前客户端已经具备 `runId` 状态轮询、确认操作状态查询和完成后历史回读，这是 SSE 事件补发前的过渡方案；事件序号、事件存储和 `Last-Event-ID` 续传仍需单独实现。

## 7. 分阶段实施清单

### 阶段一：状态与基础恢复（已完成）

- [x] `AgentState`、`AgentRun`、`AgentStep`、`AgentCheckpoint` 数据结构；
- [x] Redis 运行态、步骤和 checkpoint 存储；
- [x] 恢复扫描器、过期判断和租约；
- [x] `runId` 与运行状态查询接口；
- [x] 服务端会话历史回读接口；
- [x] 客户端恢复历史、轮询状态、显示恢复提示并自动刷新结果。

### 阶段二：写操作可靠性（已完成）

- [x] Agent 确认层统一生成、校验和原子抢占幂等键；
- [x] 确认记录保存 `PENDING`、`PROCESSING`、`CONFIRMED`、`UNKNOWN_REVIEW` 状态、结果和错误信息；
- [x] 对长时间 `PROCESSING` 增加超时转人工复核，禁止未知结果自动重试；
- [x] 提供 `GET /api/agent/confirmations/{confirmationId}` 状态查询接口，并开放前端 API；
- [x] 增加重复提交、并发处理中、超时人工复核和状态查询测试；
- [x] 保存菜谱意图支持“保存这道菜”“把它收藏起来”等自然语言表达，并避免误匹配“保存本周菜单”；
- [x] 保存意图采用“高置信规则优先 + 模型结构化意图兜底”：模型仅返回 `SAVE_RECIPE/OTHER`、置信度和菜谱指代来源，高于阈值才暴露保存工具；
- [x] 模型意图识别失败时安全降级为普通对话，不自动暴露写工具；
- [x] 增加配置开关 `AGENT_INTENT_RECOGNITION_ENABLED` 和置信度阈值 `AGENT_INTENT_RECOGNITION_CONFIDENCE_THRESHOLD`，便于灰度和成本控制；
- [x] 为库存增删改、撤销、菜谱保存、周菜单写入等底层写工具补齐 Agent 幂等键透传与统一结果查询；
- [x] Agent 写操作统一使用持久化幂等账本，幂等键透传到确认执行边界；保存菜谱原生写入也保存 Agent 幂等键，重复请求直接回放已有结果；
- [x] 增加 `GET /api/agent/writes/{idempotencyKey}`，在 SSE 断开或客户端重试时查询处理中、已完成和失败结果；
- [x] 增加真实数据库下的崩溃窗口演练；
- [x] 验收所有写工具：同一幂等键重复请求不会产生重复数据或重复扣减。

### 保存菜谱意图路由（当前实现）

保存菜谱请求按以下顺序处理，避免把普通查询误判成写操作：

```text
用户消息
  ↓
高置信规则：保存/收藏 + 菜谱指代
  ├─ 命中：直接暴露 recipe_save
  └─ 未命中但包含保存类动作词：调用 agent_intent_classify
                  ↓
        {intent, confidence, recipe_reference}
                  ↓
        confidence >= 0.85 且有菜谱指代
                  ↓
             暴露 recipe_save
                  ↓
      主 Agent 决定工具参数 → 确认 → 幂等执行
```

意图识别模型只返回结构化分类，不执行写操作；模型不可用、返回格式不合法或置信度不足时安全降级为普通对话。可通过 `AGENT_INTENT_RECOGNITION_ENABLED=false` 关闭兜底，或通过 `AGENT_INTENT_RECOGNITION_CONFIDENCE_THRESHOLD` 调整阈值。

每次运行只记录一条 `intent.resolved` 审计步骤，避免模型重试或服务恢复造成重复记录。步骤中的 `request_json` 仅保存来源、是否调用模型、消息长度和附件标记，不复制完整用户消息；`response_json` 保存 `intent`、`confidence`、`recipeReference`、`source`、路由结果和分类理由。规则命中、模型成功、模型降级、规则门控和配置关闭分别使用 `RULE`、`MODEL`、`MODEL_FALLBACK`、`RULE_GATE`、`CONFIG` 来源，便于定位“为什么暴露或没有暴露写工具”。意图解析标记会随 checkpoint 一起保存，服务重启后不会重复识别，也能恢复此前暴露的保存工具。

Agent 写工具确认后会先在 `agent_write_operations` 中以 `(user_id, idempotency_key)` 原子抢占执行权，并先提交 `PROCESSING` 记录，再调用具体业务服务。这样客户端在长耗时写入或 SSE 断开期间也能查询到处理中状态；业务写入、账本完成状态和确认完成状态仍在同一事务中提交。确定性的业务异常会单独落为 `FAILED` 并保存错误码和错误消息，进程崩溃或连接中断则保留 `PROCESSING`，不把未知结果误判为失败。账本保存操作类型、确认编号、状态、结果 JSON 和结果消息；同一幂等键重复请求不会再次调用业务写服务。客户端可以用确认事件中的幂等键查询 `GET /api/agent/writes/{idempotencyKey}`，即使原始 SSE 已断开，也能拿到最终结果或处理中状态。已有原生幂等能力的库存撤销、做菜消耗会继续收到同一个幂等键；其他 Agent 写入先由统一账本保护，后续再按业务表补齐原生幂等字段。保存菜谱还会把幂等键落到 `recipe_records.agent_idempotency_key`，在业务表层再次拦截重复插入。

阶段二验收新增 `AgentWriteOperationPersistenceIntegrationTest` 和写工具路由矩阵测试：实际数据库账本只允许同一用户的同一幂等键生成一条记录，完成后重复提交只回放结果，`PROCESSING` 状态不会被不安全重试覆盖；保存菜谱集成测试同时检查主表、食材和步骤不会重复插入。`scripts/agent-write-crash-drill.ps1` 会在真实 Docker/MySQL 环境中开启一个默认关闭的写事务暂停点，提交确认后等待“业务写入后、账本完成前”窗口，再 SIGKILL 后端并重启，验收账本仍为 `PROCESSING`、确认仍为 `PENDING`，从而证明未知结果不会被自动重试。演练结束会关闭暂停配置，不删除 MySQL/Redis 数据卷。

### 阶段三：步骤级恢复演练

- [x] 增加恢复扫描器的自动化测试：抢租约、跳过已占用租约、单个任务失败后继续扫描、Redis 暂时不可用时等待下一轮重试；
- [x] 为模型调用前后、工具调用前后、等待确认和完成节点补齐故障注入测试；
- [x] 验证从 `MODEL_DECISION`、`TOOL_EXECUTE`、`OBSERVE`、`WAITING_CONFIRMATION`、`FINALIZE` 恢复；
- [x] 验证恢复任务不会重复执行已完成的只读操作；写操作继续由确认幂等账本保护，恢复时只停留在确认态；
- [x] 验收：每个已覆盖崩溃点都能得到确定结果或安全停留在人工确认状态。

步骤级演练通过 `AgentFaultInjector` 提供可替换的崩溃注入点：模型调用前后、工具调用前后、等待确认和完成后。生产实现为空操作，测试替身抛出 `AgentCrashException`，模拟进程在最近一次 checkpoint 提交后立即退出。模型结果会先以脱敏元数据写入 `agent_steps`，并将无工具结果推进到 `FINALIZE` checkpoint；因此恢复不会重复调用模型。工具结果写入 `OBSERVE` checkpoint 后才允许恢复继续决策；等待确认的 checkpoint 只恢复为 `WAITING_CONFIRMATION`，不会自动执行写操作。`FINALIZE` 恢复会从 checkpoint 中补写尚未落库的最终文本，并按消息内容去重。

### 崩溃窗口演练工具（本分支）

仓库新增 `scripts/agent-recovery-drill.ps1`，用于在真实 Docker、Redis 和 MySQL 环境中演练一次后端进程崩溃。脚本不会删除容器、数据卷或 Redis 数据，只会对 `backend` 执行一次 `SIGKILL`，随后以较短的恢复参数启动后端：

脚本运行时输出使用 ASCII 文本，以兼容 Windows PowerShell 5.1 对无 BOM UTF-8 脚本的编码识别；本说明和操作步骤仍使用中文。

```powershell
.\scripts\agent-recovery-drill.ps1 `
  -RunId "正在生成的运行 ID" `
  -Token "当前登录用户的 JWT"
```

脚本会依次完成：

1. 用 JWT 查询 `GET /api/agent/runs/{runId}`，确认任务仍处于 `RUNNING` 或 `RECOVERING`；
2. 强制终止 backend，模拟进程在模型调用或工具调用窗口崩溃；
3. 临时使用 `stale-after=5s`、`scan-delay=2s` 启动后端，不修改项目 `.env`；
4. 等待后端健康，轮询 Redis 中同一个 `runId` 的状态；
5. 检查恢复日志，最终状态为 `COMPLETED` 或 `WAITING_CONFIRMATION` 才算通过。

演练前需要先在客户端发起一个尚未完成的请求，并立即复制该请求的 `runId`。如果任务在执行脚本前已经完成，脚本会主动拒绝，避免把一次普通完成误判成恢复成功。当前分支已通过恢复扫描器、各步骤故障注入、模型决策 checkpoint、工具执行/观察 checkpoint、等待确认和完成节点恢复测试；真实 Docker 演练需要在有进行中任务时执行上述脚本。

写操作崩溃窗口使用另一个脚本。先在客户端发起保存菜谱确认，记录 `conversationId`、`confirmationId` 和确认卡中的 `idempotencyKey`，然后运行：

```powershell
.\scripts\agent-write-crash-drill.ps1 `
  -ConversationId "会话 ID" `
  -ConfirmationId "确认 ID" `
  -IdempotencyKey "确认卡中的幂等键" `
  -Token "当前登录用户的 JWT"
```

脚本会开启一个明确标记的本地暂停点，提交确认请求后等待业务写入完成、幂等账本完成前的窗口，再只终止 backend。重启后必须看到写账本为 `PROCESSING`、确认状态为 `PENDING`；这表示事务回滚且系统拒绝自动重试未知副作用。脚本不会删除容器、MySQL/Redis 数据卷，结束时会恢复默认的关闭状态。

### 阶段四：SSE 事件续传（已完成）

- [x] 为 Agent 事件增加单调递增 `event_seq`；
- [x] 持久化事件并提供 `runId + lastEventId` 查询/补发接口；
- [x] 前端断线自动重连、补发、去重；
- [x] 验收：断线后遗漏事件完整补发，事件顺序不乱、不重复渲染。

Agent SSE 事件现在同时写入 Redis（内存模式保留测试实现），每个 `runId` 独立递增序号并设置 7 天 TTL。初次连接和恢复连接都会发送标准 SSE `id`，客户端使用 `Last-Event-ID` 与 `afterEventSeq` 请求补发；`GET /api/agent/runs/{runId}/events` 可直接查询事件，`GET /api/agent/runs/{runId}/events/stream` 会先补发遗漏事件，再轮询到任务结束。浏览器断线不会取消后端 Agent，前端小厨灵按序号去重并继续恢复。

### 阶段五：企业级观测与验收（已完成基础能力）

- [x] 记录每个 run、step、tool 的耗时、错误码和恢复次数；
- [x] 将规则命中、模型意图识别结果（意图、置信度、来源）写入 `agent_steps`，支持路由审计；
- [x] 增加运行成功率、恢复成功率、重复执行拦截率等指标；
- [x] 为敏感字段、用户数据和工具参数做脱敏；
- [x] 建立可重复的 Docker 重启、Redis 持久化和数据库持久化演练；
- [x] 形成面试可展示的运行时间线、故障注入报告和验收报告。

阶段五基础指标由 `AgentMetrics` 统一记录，并通过管理员可访问的
`/actuator/metrics/{name}` 暴露：`agent.runs.started`、
`agent.runs.completed`、`agent.runs.failed`、`agent.runs.recovered`、
`agent.runs.duration`、`agent.events.persisted`、`agent.events.replayed` 和
`agent.writes.duplicate`。事件与审计数据继续遵守脱敏边界，不写入 API Key、密码或完整用户原文。

自动评测集由 `AgentEvaluationService` 提供，使用固定脱敏样例检查保存菜谱、周菜单、库存写入、菜谱查询和普通聊天的工具路由边界，不调用真实模型。管理员可通过
`POST /api/admin/dashboard/agent-evaluation/run` 手动执行，并通过
`GET /api/admin/dashboard/agent-evaluation` 查看最近一次结果；服务默认每日自动执行并将运行摘要和每个用例结果写入 MySQL。
可通过 `AGENT_EVALUATION_ENABLED`、`AGENT_EVALUATION_INTERVAL` 和
`AGENT_EVALUATION_INITIAL_DELAY` 控制调度。

## 8. 总验收标准

以下条件全部满足后，才算完成本方案：

```text
模型调用后重启，可以继续执行
只读工具完成后重启，不重复执行已完成步骤
等待确认时重启，不自动执行写操作
写操作重复提交不会产生重复数据
写操作处于未知结果时能查询或进入人工复核
SSE 断开后能补发遗漏事件并继续接收实时事件
客户端刷新或服务重启后能恢复历史消息和运行状态
Redis、数据库、恢复扫描和租约均有自动化测试
敏感日志不包含 API Key、密码或验证码
```

## 9. 每次功能交付的固定提醒格式

每实现一个功能，回复结尾固定给出：

```text
本次完成：...
验证结果：...
剩余功能：...
下一步推荐：...
```

下一步默认推荐阶段五“企业级观测与验收”的持续运营化：接入告警、指标看板和自动评测集。所有阶段完成并通过总验收后，再删除本方案内容。
