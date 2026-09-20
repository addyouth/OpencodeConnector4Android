# OConnector v2 适配设计 (branch: v2-adapt)

目标：fork 同时支持 **opencode v1 (serve)** 与 **opencode v2 (`serve` v2 API)**，
按服务器逐个切换版本；并落地手机端体验需求（进会话自动带主代理、只显示勾选模型）。

地面真相来源：opencode **v2.0.10** standalone 二进制实测（`GET /openapi.json` 全量 spec，
测试服跑在 `127.0.0.1:4099`，spec 存档见维护者本地 `octest/v2-openapi.json`）。

## 0. 总体设计：双模式（dual-mode）

- 现有 v1 代码（`OpenCodeApiClient` + v1 DTO）**原样保留**，继续服务 v1 服务器。
- 新增 `data/api/v2/`：`OConnectorApiV2Client` + `dto/v2/*` + `OpenCodeSseV2Client`。
- Repository 层按服务器配置的 `serverVersion: v1 | v2` 分发；UI 按版本隐藏 v2 不支持的面板（Todo）。
- 鉴权层**不用改**：v2 全站（含 `/doc`）同样 `Basic opencode:<server-password>`。

## 1. 端点映射 v1 → v2

| 功能 | v1 | v2 | 备注 |
|------|----|----|------|
| 会话列表 | `GET /session?list&directory=&scope=` + `x-opencode-directory` 头 | `GET /api/session?directory=&project=&parentID=&limit=&order=&search=&cursor=` | v2 返回 `{data[], cursor}` 分页包，不再是裸数组；无需自定义头 |
| 建会话 | `POST /session` `{}` + directory | `POST /api/session` `{title?, agent?, model?: Model.Ref, location?}` | v2 建会话可直接指定 agent/model |
| 会话详情/删除 | `GET/DELETE /session/{id}` | `GET/DELETE /api/session/{sessionID}` | 同形 |
| fork | `POST /session/{id}/fork` | `POST /api/session/{sessionID}/fork` | 同形 |
| 中断 | `POST /session/{id}/abort` | `POST /api/session/{sessionID}/interrupt` | 改名 |
| 撤销 | `POST /session/{id}/revert {messageID}` | `POST .../revert/stage` + `POST .../revert/commit`（两段式），另有 `DELETE .../revert` | v2 两段式，需 UI 保持 stage→commit 连续调用 |
| 消息列表 | `GET /session/{id}/message?limit=` | `GET /api/session/{sessionID}/message?limit=&order=&cursor=&type=` → `{data, cursor}` | 分页包 |
| 发消息 | `POST /session/{id}/prompt_async {parts, agent, model, variant}` | `POST /api/session/{sessionID}/prompt {text*, files?, agents?, skills?, delivery?, resume?}` | **v2 prompt 只收 text**；agent/model 改为会话级设置（`POST .../agent`，`POST .../model {Model.Ref}`），发送前先对齐再 prompt |
| 权限回复 | `POST /permission/{req}/reply` | `POST /api/session/{sessionID}/permission/{requestID}/reply` | 改为会话作用域 |
| 提问回复 | `POST /question/{req}/reply\|reject` | `POST /api/session/{sessionID}/form/{formID}/reply` | question → form，字段待联调时抓包 |
| Todo | `GET /session/{id}/todo` | **无（端点/schema 双无）** | v2 模式隐藏 Todo 面板 |
| 忙闲状态 | `GET /session/status` → map | `GET /api/session/active`（仅活跃 ID） | 绿点逻辑改为"在活跃集合中即忙" |
| 子会话 | `GET /session/{id}/children` | `GET /api/session?parentID={id}` | 等价 |
| 项目发现 | `GET /project` + `GET /project/current`（ProjectInfo.worktree） | `GET /api/project` → `Project[]`（无 worktree）；会话按 `?project={id}` 过滤 | Project 无 worktree 字段；`?project=` 取 id 还是 canonical，联调验证 |
| agent 列表 | `GET /agent` | `GET /api/agent`（另有 `GET /api/agent/{agentID}`） | 待验证返回包形状 |
| 文件浏览/读 | `GET /file?path=`，`GET /file/content?path=` | `GET /api/fs/list`，`GET /api/fs/find`，`GET /api/fs/read/*` | 参数名待联调验证 |
| provider/模型 | `GET /provider` → `{all, default, connected}` | `GET /api/model` → `{location, data: Model.Info[]}`（另有 `GET /api/model/default`，`GET /api/provider` 不再含模型表） | **Model.Info 必带 `enabled: boolean` + `status`** → 需求③直接过滤 `enabled` |
| 事件流 | v1 SSE（message.part.delta 等） | `GET /api/event` → `text/event-stream`（`event.subscribe`，V2EventEncoded） | 事件族全换，需抓包重写 SseEventBus 映射 |

## 2. 需求落点

- **需求②（全项目 + 主代理）**：`Session.Info` 原生带 `agent: string` + `model: Model.Ref`。
  进会话时 `ChatSelectionState.committed` 默认填会话的 agent/model（用户可再改）。
  v1 路径保持现状（无 agent 字段，可选：从最后一条消息 `info.agent` 推断）。
- **需求③（只显示勾选模型）**：v2 按 `Model.Info.enabled == true` 过滤（`status` 异常的一并隐藏，
  规则待联调确认）；v1 路径改为只列 `connected` providers 的模型（替代现状列 `all`）。
- **Todo 面板**：v2 模式隐藏（无 API）；v1 模式保留。

## 3. 实施阶段

1. CI：加 `.github/workflows/android.yml`（JDK 17 + `assembleDebug` + 上传 APK artifact）。
2. v2 DTO + ApiV2Client + SseV2Client（对照本表 + 实测抓包）。
3. Repository 双模式分发 + 服务器设置加版本切换。
4. UI：需求②③ + v2 隐藏 Todo。
5. 联调：v2 测试服（4099）全流程；v1 回归（现役 4096 不动）。
6. 打 tag 出 release APK，真机验证。

## 4. 已知待验证（联调时逐项打勾）

- [ ] `GET /api/session?project=` 取 project id 还是 canonical
- [ ] `GET /api/agent` 返回包形状（数组还是包对象）
- [ ] `GET /api/event` 事件名与字段（抓一段真实 stream）
- [ ] form 回复字段（Form.Detail/Reply）
- [ ] revert stage→commit 参数
- [ ] `GET /api/experimental/migration/v1` 是否可辅助迁移
