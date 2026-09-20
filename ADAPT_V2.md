# OConnector v2 适配方案（v1 草稿，2026-09-20）

> **实施状态（2026-09-20 Round 1 数据层完成）**：
> - ✅ 实测推翻下方若干猜测（以本入口为准）：`/api/config` 返回**解析后**配置（whitelist 已被 v2 丢弃），
>   原始 whitelist 只有 `GET /api/fs/read/{path}` 读原始 opencode.json 才有 → **需求③最终方案：
>   客户端读服务端 opencode.json 原始内容，解析 `provider.*.whitelist` 过滤 /api/model 结果**（不依赖服务端支持）；
> - ✅ v2 无 `/todo`、`/children`、`/session/status`、`/question/*`（实测 404）→ 优雅降级空结果；
> - ✅ v2 revert 为三阶段 `stage → commit`，v1 单调用语义 best-effort 降级；
> - ✅ `GET /api/session` 一次返回**全部项目**会话（`{data, cursor}`，每条带 agent/model）→ 需求②天然满足；
> - ✅ `POST /api/session` body `{title?, agent?, location{directory}?}`，不传 agent 默认 `build`（需求②必须显式传）；
> - ✅ `GET /api/agent` → `{location, data:[Agent.Info]}`；Agent.Info.mode ∈ subagent|primary|all；
> - ✅ `GET /api/project` → **裸数组**（无 data 包装）；Project.Info.canonical = v1 worktree；
> - ✅ v2 Model.Ref = `{id, providerID, variant}`（用 `id` 非 `modelID`）→ MessageModel/AgentModel 加 `id` 兼容；
> - ✅ variants 由 v1 Map 变 v2 **Array**（翻译层转 Map 保 UI 兼容）；
> - ✅ SSE `/api/event` 翻译层已建（V2SseTranslation.kt）：step.started→message.updated(assistant锚点)、
>   reasoning/text.delta→message.part.delta、reasoning/text.ended→message.part.updated、
>   step.ended→message.completed、execution.succeeded→session.idle、execution.failed→session.error(+idle)；
> - ✅ v2 消息判别联合 → v1 MessageInfo 手动翻译已建（V2MessageParser.kt，content[] 多态安全解析）；
> - ✅ 数据链路完成：DTO v2 字段 + OpenCodeApiClient 全量重写（/api 前缀 + {data} 解包 + whitelist 读取）
>   + OpenCodeRepository.listProviders 改为「/api/provider + /api/model + whitelist 过滤」；
> - ⬜ Round 2 UI（ChatViewModel.buildModelOptions 过滤消费 / buildModelOptions 已就绪、进会话默认 agent、
>   会话列表 agent/项目显示）→ 见 git log commit "Round 1"。

目标：fork OConnector，适配 opencode v2（2.0.x）server API，同时落地两个体验需求：
- 需求②：显示所有项目，进入会话自动带出该会话主代理
- 需求③：会话页面只显示「勾选（启用）」的模型，而非 provider 全部模型

## 0. 背景事实（已实查）

- 现役 4096 = opencode v2 service（0.0.0.0:4096 + Basic 密码），`GET /openapi.json` 有完整 spec（249KB，本地 D:\dev\octest\v2-openapi.json）
- v2 REST 全部 `/api/` 前缀；认证 Basic（`opencode:<password>`）
- v2 事件流：`GET /api/event` = SSE，每行 data 是扁平 JSON：
  `{id, created, type, location:{directory}, data:{...}, durable?:{aggregateID,seq,version}}`
  已见类型：server.connected / session.step.started / session.reasoning.delta /
  session.tool.success / shell.exited / shell.deleted / provider.updated / model.updated
- 事件是全局流（跨项目），用 `location.directory` 区分项目；含 `: heartbeat` 心跳

## 1. v2 数据模型（对照 v1 DTO）

| 概念 | v1（现状 DTO） | v2（新 DTO） | 说明 |
|------|----------------|--------------|------|
| 会话 | SessionInfo（id/slug/projectID/directory/title/time/parentID） | Session.Info：id, parentID, fork, **projectID, agent, model**, cost, tokens, outcome, time, title, subpath, metadata, permissions, revert, location | **有 agent+model 原生字段 → 需求②数据源** |
| 消息 | MessageInfo{info, parts[]} | Session.Message.Info **判别联合**：User / Synthetic / System / Skill / Shell / ProviderState / Assistant.Text / Assistant.Reasoning / ToolState.* / Assistant.Tool / Assistant.Retry / Compaction.* / Idle / AgentSelected / ModelSelected / LocationSwitched 等 | 序列化需 JsonClassDiscriminator |
| 模型 | ProviderList{all, default, connected}（无 enabled） | Model.Info：id, modelID, providerID, canonical, family, name, compatibility, package, settings, headers, body, capabilities, variants, time, cost, status, **enabled**, limit | **enabled = 勾选 → 需求③过滤 enabled==true** |
| 代理 | AgentInfo(name, mode, hidden, description, model) | Agent.Info：id, name, model, request, system, description, mode, hidden, color, steps, permissions | |
| Provider | ProviderInfo(id, name, models) | Provider.Info：id, canonical, integrationID, name, activation, ... | |
| 项目 | ProjectInfo(id, worktree, time, sandboxes) | Project.Info（待确认） | 发现项目列表：GET /api/project |

## 2. v1 → v2 端点映射（OpenCodeApiClient.kt 重写面）

| v1（现状） | v2 | 备注 |
|-----------|----|------|
| GET /session?list | GET /api/session?cursor= | SessionsResponse{data, cursor} |
| POST /session | POST /api/session | 创建会话 |
| GET /session/{id} | GET /api/session/{id} | 返回 {data: Session.Info} |
| DELETE /session/{id} | DELETE /api/session/{id} | |
| POST /session/{id}/fork | POST /api/session/{id}/fork | |
| POST /session/{id}/abort | POST /api/session/{id}/interrupt | v2 用 interrupt（spec 有 /interrupt） |
| POST /session/{id}/prompt_async | POST /api/session/{id}/prompt | body {text, agents?, files?...}（text 必填） |
| GET /session/{id}/message | GET /api/session/{id}/message | SessionMessagesResponse{data, cursor} |
| GET /session/{id}/todo | （未确认；v2 可能并入 context/其他） | |
| GET /session/status | GET /api/session/active | |
| GET /session/{id}/children | 由 Session.Info.parentID/fork 推导 | |
| POST /permission/{id}/reply | POST /api/session/{id}/permission/{requestID}/reply | |
| POST /question/{id}/reply | （v2 用 form？/api/session/{id}/form/{formID}/reply） | 待确认 |
| GET /project/current | GET /api/project（或 /api/location） | 待确认 |
| GET /project | GET /api/project | |
| GET /agent | GET /api/agent | |
| GET /provider | GET /api/provider + GET /api/model | **模型列表主要用 /api/model** |
| GET /file?path= | GET /api/fs/list、/api/fs/read/*、/api/fs/find | |
| POST /session/{id}/revert | POST /api/session/{id}/revert | |
| SSE GET /global/event | SSE GET /api/event | 格式完全不同（见 §0） |
| （无） | POST /api/session/{id}/agent | **设置会话 agent → 需求②** |
| （无） | POST /api/session/{id}/model | 设置会话 model |

## 3. 改造文件清单（估计）

### 3.1 DTO 层（重写核心）
- `data/api/dto/SessionDtos.kt` → v2 Session.Info + SessionMessagesResponse + v2 prompt 请求/响应
- `data/api/dto/CommonDtos.kt` → v2 Model.Info / Agent.Info / Provider.Info / Project.Info / Location
- `data/api/dto/EventDtos.kt` → v2 EventEnvelope + 判别联合消息
- `data/api/dto/ServerDtos.kt` → 保留（本地连接配置，与 server 无关）

### 3.2 API 层
- `data/api/OpenCodeApiClient.kt` → 端点路径/参数/请求体按 §2 重写
- `data/api/OpenCodeSseClient.kt` → SSE 端点 /api/event + 新解析（type/data/location/durable）

### 3.3 Repository / 状态层
- `data/repository/OpenCodeRepository.kt` → 方法实现按新 API 调整（listSessions/listAllSessions/getMessages/sendMessage/listProviders/listAgents/listProjects 等）
- `data/sse/SseEventBus.kt` → 适配新事件类型分拣
- `data/sessionstore/ChildSessionStore.kt` → parentID/fork 推导替代 children 接口

### 3.4 UI 层（需求②③落点）
- `ui/chat/ChatViewModel.kt`：
  - 需求③：模型列表改为 `GET /api/model` → 过滤 `enabled==true`（buildModelOptions 改造）
  - 需求②：进入会话时读 `Session.Info.agent`，默认选中该 agent（切换时 POST /api/session/{id}/agent）
- `ui/sessions/SessionsViewModel.kt`：会话卡片显示 agent（若需要）
- `ui/chat/ChatSelectionState.kt`：agent 选择逻辑适配（v2 agent id 字段）

### 3.5 测试
- `app/src/test/.../*Test.kt`：DTO 解析测试改为 v2 样本；SSE 解析测试用实抓样本（§0）

## 4. 风险与暂缓
- v2 仍是 beta（2.0.x），/api 可能会有 breaking change → 适配层集中封装，隔离变化
- /api/event 类型全集未穷尽：以实抓 + 增量补充为准；未知类型跳过不崩
- 子会话/todo/question 的 v2 等价端点待确认 → 先配置开关，缺失功能不阻塞主流程

## 5. 验证路径
1. 本地 4099 测试服（opencode2 serve）或现役 4096 service 作为联调 target
2. 每次改动 → 本机单测（DTO/SSE 解析测试）
3. 周期 push → Actions assembleDebug → APK → 手机安装真机验证