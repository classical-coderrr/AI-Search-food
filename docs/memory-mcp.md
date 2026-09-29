# 小厨灵长期记忆 MCP 接口

后端可通过标准 MCP Streamable HTTP 暴露个人长期记忆工具。该能力默认关闭；启用前应确保 MCP 客户端只连接可信服务，并通过 HTTPS 传输访问令牌。

## 启用和连接

- 环境变量：`MEMORY_MCP_ENABLED=true`（默认 `false`）
- MCP 地址：`/api/memory/mcp`
- 传输：Streamable HTTP
- 鉴权：使用当前登录用户的 JWT Bearer Token；管理员令牌不允许访问

服务端从 JWT 认证主体取得用户 ID，不接受 MCP 参数传入 `userId`。MCP 会话与创建会话的用户绑定；其他账号即使拿到会话 ID 也不能复用该会话。所有工具调用继续复用现有记忆服务，因此沿用现有的数据隔离、画像关闭策略、所有权校验、版本校验和幂等规则。

## 工具

| 工具 | 用途 | 写入行为 |
| --- | --- | --- |
| `memory.search` | 按当前任务检索个人记忆 | 否 |
| `memory.profile.get` | 读取结构化画像与可管理记忆 | 否 |
| `memory.episodes.list` | 查询个人事件记忆 | 否 |
| `memory.recipe.history` | 查询个人菜谱行为历史 | 否 |
| `memory.skill.get` | 获取当前任务的个性化策略 | 否 |
| `memory.episode.save` | 保存用户明确表达的偏好事件 | 是，需 MCP 用户确认弹窗 |
| `memory.preference.update` | 修改已存在的长期偏好 | 是，需 MCP 用户确认弹窗和记忆版本 |
| `memory.feedback.record` | 记录已实际使用记忆的用户反馈 | 是，需 MCP 用户确认弹窗 |

若客户端不支持 MCP 表单确认（elicitation），写入工具会拒绝执行，不会降级为信任模型自报的确认字段。个性化已关闭时，不允许通过 MCP 读取或写入个人记忆。

`memory.episode.save` 当前只接受显式偏好声明，不允许外部模型任意写入原始事件；搜索、收藏、烹饪、评分等事实仍由应用现有业务流程生成 Episode。
