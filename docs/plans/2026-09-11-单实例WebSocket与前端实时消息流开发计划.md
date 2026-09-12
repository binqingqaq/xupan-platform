# 单实例 WebSocket 与前端实时消息流开发计划

> **给实施者：** 必须逐项完成本计划；每个任务完成后先运行对应验证，再进入下一个任务。不得把本计划中的设计、静态检查或命令结果表述为已经实现的功能。

**目标：** 在当前已经完成的聊天室消息持久化与 REST 纵向切片基础上，实现单实例正式 WebSocket 实时消息流，使同一聊天室的多个在线用户可以低延迟收发消息，并具备安全的短期连接票据、断线重连、游标补偿、幂等去重、连接治理和 REST 降级路径；本阶段不实现机器人业务、不引入 Redis、不做多实例广播。

**架构：** Spring Boot WebSocket + 数据库短期 WS ticket + 进程内连接注册表 + 事务提交后事件广播 + 数据库游标补偿 + Vue3 客户端状态机。REST 仍是历史查询、消息持久化、读游标和降级通道；WebSocket 只承载实时传输，不绕过现有权限和消息写入服务。

**技术栈：** Java 21、Spring Boot 4.0.8、Spring Security、Spring WebSocket、MyBatis、Flyway、MySQL 8.4 LTS、H2 测试、Vue 3、TypeScript、Vite。

## 全局约束

- 当前代码、配置、迁移和测试结果优先于参考项目；`caipiaowan-main` 只提供可借鉴的行为观察，不是当前项目的需求或依赖。
- 当前已完成的用户、角色、权限、登录会话、短期 WS ticket、聊天室房间、消息、出站事件和 REST 消息链路必须复用，不重新造一套认证或消息持久化逻辑。
- WebSocket 连接必须先通过已登录会话签发的短期 ticket；不能在 WebSocket 帧中发送明文用户 ID、客户端昵称、头像或权限信息作为可信身份。
- WebSocket 收到的用户消息必须进入现有 `ChatMessageService.sendUserMessage(...)`，由服务端生成发送者快照、序列号和持久化记录；广播内容来自服务端持久化结果。
- 不能把用户可控 HTML、JavaScript、`eval` 或富文本原样注入聊天室；前端按纯文本展示并转义。
- 单实例范围内允许进程内广播；部署为多实例前必须引入受控的 outbox consumer / Redis pub-sub / 消息中间件设计，不能直接共享本地连接注册表。
- 本阶段不实现机器人、机器人模板、自动触发、下注、支付、开奖、结算、Redis、多实例广播或正式生产高可用。
- 继续使用 Flyway 管理数据库；本阶段预期不新增表，除非实施过程中发现当前 ticket 或 outbox 契约存在经过测试确认的缺口，且必须先更新方案和本计划。
- 每个任务单独提交 Git，提交信息使用中文或中英文结合；不得提交 `docs/本地环境密钥.md`、真实凭据、日志、`target/` 或浏览器会话信息。

---

## 1. 当前基线和阶段边界

### 1.1 已完成并必须复用的能力

- 用户登录、刷新、注销、当前用户查询和会话安全版本校验已存在于 `server/xupan-server/src/main/java/com/xupan/server/auth/web/AuthController.java` 与 `server/xupan-server/src/main/java/com/xupan/server/auth/service/TokenService.java`。
- `POST /api/auth/ws-ticket?roomCode=...` 已由登录态签发 60 秒、绑定用户/会话/房间的短期票据；数据库通过 `auth_ws_ticket` 的哈希、过期时间、使用状态、会话吊销状态和用户安全版本校验票据。
- 聊天 REST 已存在于 `server/xupan-server/src/main/java/com/xupan/server/chat/web/ChatController.java`，包括房间、历史消息、发送消息和读游标。
- `ChatMessageService.sendUserMessage(...)` 已包含用户状态、房间状态、禁言、内容长度、客户端消息 ID 幂等、房间序列号、服务端发送者快照和 `chat_outbox` 写入。
- 前端 `web/src/views/UserRoom.vue` 已能加载历史消息、发送 REST 消息、按 `afterSequence` 轮询并按消息 ID/序列号合并；本阶段只替换实时通道，不破坏历史加载和降级发送。

### 1.2 当前阶段明确不做的事项

- 不增加机器人表、机器人管理、机器人消息模板、机器人自动发言或机器人触发器；这些属于方案第 5 阶段。
- 不把 `chat_outbox` 直接当成已完成的多实例消息总线；本阶段只在事务提交后给本机连接广播，outbox 作为后续可靠发布边界保留。
- 不新增支付系统或真实充值；余额仍按已确认的后台人工分配方案执行，与本阶段 WebSocket 无关。
- 不用 WebSocket 替代 REST 的登录、房间查询、历史查询、权限判断和消息落库。

### 1.3 阶段完成判定

只有同时满足以下条件，阶段 4 才能标记完成：

1. 两个已登录浏览器窗口进入同一房间，任一方发送后另一方能收到服务端持久化后的消息，且发送方不会重复显示。
2. WebSocket 断线重连后，客户端能凭最后确认序列从 REST 补齐缺失消息，并且重连补偿与实时消息不重复。
3. 未登录、ticket 过期、ticket 已使用、ticket 与房间不匹配、会话已注销或用户已禁用时，WebSocket 不能建立可用连接或不能发送消息。
4. 心跳、连接数上限、单用户连接上限、慢消费者和服务重启关闭行为均有测试或可复现的验收记录。
5. 前端在桌面宽度和 375px 移动宽度可用；WebSocket 不可用时页面仍能通过 REST 降级收发。
6. H2 全量测试、MySQL 8.4.11 聊天专项测试、前端类型检查和构建均通过；双浏览器人工验收记录已更新。

## 2. legacy 参考审计和迁移决策

### 2.1 可以借鉴的机制

`D:\\codes\\liuhecai\\_caipiaowan_extract\\caipiaowan-main` 中已确认有两类可借鉴内容：

| 参考位置 | 观察到的能力 | 当前项目的采用方式 |
| --- | --- | --- |
| `Application/Home/Controller/WorkermanlhcController.class.php` | Workerman 按游戏端口维护连接并广播系统/开奖类消息 | 只借鉴“连接注册表 + 广播 + 定时心跳”的运行模型；当前使用 Spring WebSocket，并按当前聊天室房间隔离 |
| `start_io.php` | Socket.IO 连接映射、在线连接管理、服务端向指定用户或全体推送 | 只借鉴“连接索引和定向广播”的概念；当前改为 ticket 认证、服务端用户身份、房间广播和权限校验 |
| `Application/Admin/Controller/RobotController.class.php` | 机器人 CRUD、机器人消息模板、管理员发消息入口 | 作为第 5 阶段领域需求参考；当前阶段只预留广播接口，不迁移机器人数据模型或管理接口 |
| `Template/Home/Run/js/lot_im.html`、`jincailhc.html` | 客户端 WebSocket 事件流、重连/心跳类行为、消息列表更新 | 只借鉴事件流分层和断线补偿目标；不复用客户端自报身份、客户端触发机器人、原始 HTML 拼接和 `eval` |

### 2.2 明确拒绝的 legacy 机制

- `start_io.php` 的客户端发送原始 `uid` 登录不满足当前会话安全要求，必须由一次性 ticket 映射服务端用户。
- `start_io.php` 的 `12224/12225` 内部 HTTP 发布口没有当前项目所需的服务间认证、权限、审计和幂等，不能作为现行接口。
- legacy 客户端自带昵称、头像和机器人触发比例，不能成为当前消息的可信字段或业务规则。
- legacy 使用字符串拼接 HTML 和 `eval` 处理消息，当前前端必须以结构化 JSON + 纯文本渲染替代。
- legacy 的订单、积分、余额变更与机器人消息混在发送流程中，不能移植到当前聊天室实时链路。
- legacy 的端口、启动脚本、PHP Workerman/Socket.IO 进程不能作为当前 Spring Boot 运行时依赖。

### 2.3 迁移后的正式边界

当前实现只复用“实时连接管理”和“广播时机”的抽象，不复制 legacy 的协议、认证、端口、数据库表、机器人逻辑或客户端脚本。后续机器人阶段必须通过受控的服务端领域服务生成系统消息，继续使用同一条消息持久化与广播管道。

## 3. 协议和状态机冻结

### 3.1 WebSocket 连接建立

1. 前端先调用现有 `POST /api/auth/ws-ticket?roomCode={roomCode}`，从响应取得一次性 ticket。
2. 前端连接 `GET /ws/chat/{roomCode}?ticket={ticket}`；ticket 只允许在握手阶段出现，服务端立即消费，不保存原文。
3. 握手成功后服务端把用户、会话、房间和连接绑定到 `ChatConnectionRegistry`；WebSocket 帧中不再接受身份字段。
4. ticket 不可用时返回明确的握手失败状态；前端清理内存票据并重新走刷新/登录态流程，不把 ticket 放入 localStorage、URL 历史或日志。

### 3.2 客户端到服务端消息

```json
{"type":"subscribe","roomCode":"main"}
{"type":"message.send","clientMessageId":"uuid","content":"纯文本消息"}
{"type":"cursor.ack","sequence":123}
{"type":"ping","nonce":"uuid"}
```

- `subscribe` 只允许订阅握手时绑定的房间，用于显式完成同步状态；不能切换到其他房间。
- `message.send` 的 `clientMessageId` 必须是客户端生成的稳定 UUID；内容仍由后端 `ChatMessageService` 做规范化、长度和禁言校验。
- `cursor.ack` 只更新当前用户在当前房间的已读游标，不接受大于服务端已存在最大序列的游标。
- `ping` 只用于连接保活；服务端不把它当作业务消息或机器人触发器。

### 3.3 服务端到客户端消息

```json
{"type":"connected","connectionId":"opaque-id","roomCode":"main","serverTime":"2026-09-11T12:00:00Z"}
{"type":"sync.required","afterSequence":123}
{"type":"message.created","message":{}}
{"type":"message.ack","clientMessageId":"uuid","message":{},"deduplicated":false}
{"type":"cursor.ack","sequence":123}
{"type":"pong","nonce":"uuid"}
{"type":"error","code":"CHAT_MESSAGE_REJECTED","message":"消息发送失败"}
{"type":"sync.complete","afterSequence":123,"latestSequence":130}
```

- `message.created` 是房间广播事件；`message.ack` 只返回给发送连接，重复提交时 `deduplicated=true`。
- `sync.required` 表示客户端应通过现有 REST `GET /api/chat/rooms/{roomCode}/messages?afterSequence=...` 补偿，不在 WebSocket 内复制历史分页逻辑。
- 错误码只能表达客户端需要处理的稳定分类，不泄露 SQL、token、内部堆栈或其他用户信息。
- 所有时间使用 ISO-8601 UTC；消息对象沿用当前 REST `ChatMessageResponse` 字段，不能额外信任客户端字段。

### 3.4 状态机和时序

```text
DISCONNECTED
  -> REQUESTING_TICKET
  -> CONNECTING
  -> CONNECTED
  -> SYNCING (REST afterSequence 补偿)
  -> READY
  -> DISCONNECTED / RECONNECT_WAIT
```

- 初始连接和每次重连都必须先重新申请 ticket。
- 重连等待使用指数退避 1s、2s、4s、8s、16s，最大 30s，并加入 0-250ms 随机抖动。
- 客户端以 `lastReceivedSequence` 为唯一补偿游标；合并时按消息 ID优先、序列号兜底，最终按序列号排序。
- 服务端重启、连接关闭或 heartbeat 超时都视为可重连断开；鉴权失败、禁用用户和权限失败则停止重连并回到登录/无权限状态。

## 4. 实施任务

### Task 1：冻结依赖、配置和协议类型

**目标：** 先建立单一协议模型，避免后端、前端和测试各自定义不同的事件名或字段。

**文件：**

- 修改 `server/xupan-server/pom.xml`，加入 Spring WebSocket 所需的当前 Spring Boot 兼容依赖，并确认没有重复引入旧版 WebSocket 容器。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatProtocol.java`，集中定义事件类型、错误码、连接状态和消息 JSON 映射。
- 新增 `web/src/types/chat.ts`，定义前端事件联合类型、消息对象、连接状态和同步结果。
- 修改 `server/xupan-server/src/test/resources/application-test.yml` 或当前测试配置，仅补充必要的 WebSocket 测试开关，不写真实凭据。

**实现要求：**

- 后端协议类型必须使用结构化对象映射，禁止在 handler 中拼接 JSON 字符串。
- 后端入站消息必须限制 JSON 大小、事件类型和字段数量；未知事件返回稳定错误并不关闭正常连接，协议解析错误达到阈值后关闭连接。
- 前端类型必须区分 `message.created`、`message.ack`、`sync.required`、`error` 和 heartbeat 事件；不能用 `any` 绕过类型检查。
- 明确 `clientMessageId`、`message.id`、`message.sequence` 的数据类型及非空约束，和 REST DTO 保持一致。

**验证：**

```powershell
Set-Location D:\codes\liuhecai\xupan-platform\server\xupan-server
.\mvnw.cmd -DskipTests compile
Set-Location D:\codes\liuhecai\xupan-platform\web
npm run typecheck
```

新增协议序列化/反序列化测试，覆盖合法事件、未知事件、缺少字段、超长消息、非法 JSON 和错误码映射。通过后提交：`feat: 冻结聊天室实时协议模型`。

### Task 2：实现 WS ticket 握手和 Spring Security 边界

**目标：** 让 WebSocket 连接只能由已登录、未吊销、未禁用、拥有房间查看权限且 ticket 绑定正确的用户建立。

**文件：**

- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatWebSocketConfig.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatWebSocketHandler.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatWebSocketHandshakeInterceptor.java`。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/auth/service/TokenService.java`，只在确有需要时复用或补充现有 `consumeWsTicket` 契约，不重复实现 ticket 查询。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/config/SecurityConfig.java`，明确 HTTP 握手路径与静态页面访问的边界。

**实现要求：**

- 注册 `/ws/chat/{roomCode}`，从握手请求取 ticket，调用现有 `TokenService.consumeWsTicket(...)`，把已验证的主体放入 WebSocket session attributes。
- 不把原始 ticket 写入异常日志、连接 ID、业务消息或客户端回显；连接 ID 使用随机不透明值。
- 握手时检查房间代码格式和当前用户的 `PERM_CHAT_ROOM_READ`；消息发送再检查 `PERM_CHAT_MESSAGE_SEND`，不能只依赖握手时权限。
- 仅允许配置中的前端 Origin；本地开发允许 `http://127.0.0.1:5173`、`http://localhost:5173`，生产值必须由环境配置提供，不得无条件允许 `*`。
- 统一处理握手失败、协议错误、权限失败和服务关闭，关闭码与错误信息不泄露内部实现。

**测试：**

- 合法 ticket 只能使用一次。
- 过期、已使用、房间不匹配、会话吊销、用户禁用和无房间权限均失败。
- ticket 申请接口未登录仍返回未认证；握手不会被匿名绕过。
- 连接建立后把身份放入服务端上下文，客户端伪造 `userId`、昵称或头像不影响主体。
- Origin 白名单拒绝未知来源。

通过后提交：`feat: 接入聊天室 WebSocket 票据握手`。

### Task 3：实现连接注册表、心跳和慢连接治理

**目标：** 复用 legacy 的“连接索引 + 广播”运行模型，但实现正式的资源边界和连接生命周期。

**文件：**

- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatConnectionRegistry.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatConnection.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatHeartbeatScheduler.java`。
- 修改 `server/xupan-server/src/main/resources/application.yml`，新增 `xupan.chat.websocket` 的可配置项。
- 修改 `server/xupan-server/src/test/resources/application-test.yml`，设置较小但明确的测试参数。

**默认参数：**

- 单用户最多 3 个连接。
- 单实例最多 5000 个连接。
- 服务端每 20 秒发送 ping；连续 30 秒未收到 pong 的连接关闭。
- 单连接待发送队列上限 100 条；慢连接超过上限关闭并记录原因。
- 单消息最大 4096 字节；单连接每秒业务消息最多 10 条，超限返回限流错误。

**实现要求：**

- 维护 `roomCode -> connections`、`userId -> connections` 和 `connectionId -> connection` 的一致性；注册、移除、广播必须线程安全。
- 连接关闭、握手失败、异常、用户主动 logout 和应用 shutdown 都必须移除索引。
- 同一用户达到连接上限时关闭最旧连接，并在日志中记录用户 ID、房间和关闭原因，不记录 ticket。
- 广播只能向当前房间且仍处于 READY/CONNECTED 的连接发送；不能遍历全局连接把私人数据发错房间。
- 广播执行不能阻塞消息持久化事务；单连接写出失败只关闭该连接并继续其他连接。
- 使用 Spring 调度器或 WebSocket 原生心跳能力，不创建每个连接一个永久线程。

**测试：**

- 注册/移除幂等、重复关闭不抛异常、同用户和同房间索引一致。
- 单用户和全局连接数上限。
- 心跳超时、pong 更新活跃时间、慢消费者淘汰。
- 房间广播隔离以及一个连接写出失败不影响其他连接。
- 应用关闭时所有连接收到关闭信号并从注册表清除。

通过后提交：`feat: 增加聊天室连接注册与心跳治理`。

### Task 4：把消息持久化结果接入事务提交后广播

**目标：** 保证只有数据库事务成功的消息才进入实时流，并让 REST、WebSocket、未来机器人共用同一条消息发布路径。

**文件：**

- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatMessageCreatedEvent.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatRealtimeBroadcaster.java`。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatMessageCreatedListener.java`。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/chat/service/ChatMessageService.java`。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/chat/repository/ChatOutboxRepository.java`，仅在需要读取统一事件 payload 时补充方法。
- 新增或修改 `server/xupan-server/src/test/java/com/xupan/server/chat/...` 下的服务、事件和广播测试。

**实现要求：**

- `sendUserMessage(...)` 只有在新插入消息并成功写入 outbox 后才发布 `ChatMessageCreatedEvent`；幂等命中旧消息时返回 `deduplicated=true`，不再次广播。
- 事件监听使用事务提交后语义，不能在数据库事务尚未提交时向客户端推送。
- 事件 payload 使用服务端生成的完整 `ChatMessageResponse` 或统一事件 DTO，不能重新从客户端输入构造。
- REST 发送接口仍返回现有消息结果；WebSocket 发送只调用同一 service，不复制房间锁、禁言、幂等和序列号逻辑。
- 本阶段广播失败只影响当前实例在线连接；必须保留 `chat_outbox` 的 PENDING 记录和错误观测点，为后续可靠发布做边界，不伪造已发布状态。
- 监听器必须避免把同一个应用内事件重复注册为多个广播结果；测试需验证单次广播。

**测试：**

- 提交成功后广播一次。
- 事务回滚不广播。
- 同一 `clientMessageId` 重复提交不产生第二条消息和第二次房间广播。
- 广播异常不回滚已经成功的消息事务，但必须记录可检索错误。
- REST 发送和 WebSocket 发送产生同样的消息字段、序列号和权限结果。

通过后提交：`feat: 接入聊天室消息提交后实时广播`。

### Task 5：完成 WebSocket 收发、ACK、重连补偿和游标闭环

**目标：** 把协议变成可验收的单房间实时消息流程。

**文件：**

- 修改 `ChatWebSocketHandler.java`，实现入站事件分派、发送 ACK、错误处理和连接状态转换。
- 新增 `server/xupan-server/src/main/java/com/xupan/server/chat/realtime/ChatRealtimeSyncService.java`，只编排当前用户的 after-sequence 补偿，不复制 REST Controller 权限逻辑。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/chat/service/ChatMessageService.java` 或抽取明确的只读方法，复用现有历史查询和游标校验。
- 新增协议、handler、sync 和集成测试。

**消息流程：**

1. 建连后发送 `connected`，随后要求客户端以 `subscribe` 完成房间确认。
2. 服务端查询客户端声明的最后序列；若可能存在断线窗口，发送 `sync.required`。
3. 客户端通过 REST 拉取 `afterSequence`，合并并确认已完成后发送 `cursor.ack`。
4. 客户端发送 `message.send` 后，服务端调用 `ChatMessageService`，向发送连接返回 `message.ack`，向房间连接广播 `message.created`。
5. 重复的 `clientMessageId` 返回原消息 ACK，不新增广播；不同内容使用同一 ID 返回冲突错误。

**实现要求：**

- 同步期间也可接收新消息；客户端通过消息序列合并，不能假设网络到达顺序就是数据库顺序。
- 发送失败必须返回稳定错误码；不能因为一条业务消息失败而无故断开整个连接，协议违规和限流除外。
- 用户禁言、房间关闭、消息超长、权限撤销和用户禁用必须即时拒绝发送；必要时关闭连接或要求重新鉴权。
- 游标更新调用现有 `saveReadCursor` 语义，服务端拒绝回退游标，客户端不能把本地最大值误写为服务端未知序列。
- 连接断开后不在服务端保留未确认的客户端消息；可靠性由消息落库、序列和 REST 补偿保证。

**测试：**

- 两个连接实时收发、同连接 ACK、房间隔离。
- 断线前后消息补偿、乱序合并、重复事件去重、空历史同步。
- REST 和 WebSocket 交错发送的序列一致性。
- 幂等重复、内容冲突、无权限、禁言、房间关闭、超长输入和限流。
- 服务重启后的重新连接和补偿。

通过后提交：`feat: 完成聊天室 WebSocket 消息闭环`。

### Task 6：实现前端 ChatSocket 客户端服务

**目标：** 将 WebSocket 生命周期封装为可测试的前端服务，页面只订阅状态和消息，不直接管理原生 WebSocket。

**文件：**

- 新增 `web/src/services/chatSocket.ts`。
- 修改 `web/src/api.ts`，增加申请 WS ticket 的 API 函数，并复用现有 refresh/登录态处理。
- 修改 `web/src/types/chat.ts`，补齐服务端事件和客户端状态类型。
- 新增 `web/src/services/chatSocket.test.ts` 或项目现有测试目录下的等价测试文件。

**实现要求：**

- ticket 只存内存；申请失败按现有认证状态处理，不能把 access token 拼到 WebSocket URL。
- 暴露 `connect(roomCode, handlers)`、`sendMessage(clientMessageId, content)`、`ackCursor(sequence)`、`disconnect()`、`getState()` 等最小接口，并保证重复 connect/cleanup 幂等。
- 处理 `connected`、`sync.required`、`message.created`、`message.ack`、`cursor.ack`、`pong` 和 `error`，未知服务端事件进入可观测错误回调。
- 重连使用冻结的指数退避和抖动；页面离开、用户注销、切换房间时取消定时器和 socket，不产生后台重连。
- 收到 `sync.required` 时调用 REST after-sequence API；补偿完成后再将状态置为 READY。
- WebSocket 连续失败、浏览器不支持或握手权限失败时进入 `degraded`，页面可继续用 REST 轮询和 REST 发送；认证失败不得无限重试。
- 出站消息使用稳定 UUID；发送中的消息要有 pending 状态，ACK 成功或失败后可更新，不因为一次失败重复发送不可幂等的请求。

**验证：**

```powershell
Set-Location D:\codes\liuhecai\xupan-platform\web
npm run typecheck
npm run build
```

Mock WebSocket 测试必须覆盖正常握手、心跳、服务端消息、断线退避、组件销毁、补偿调用、权限失败和 REST 降级。通过后提交：`feat: 增加前端聊天室实时连接服务`。

### Task 7：把 UserRoom 切换为实时优先、REST 降级

**目标：** 保留现有历史加载和消息发送能力，把聊天室从固定轮询窗口改成正式实时窗口。

**文件：**

- 修改 `web/src/views/UserRoom.vue`。
- 必要时修改 `web/src/components/...` 下当前聊天室已有消息列表或输入组件；若没有独立组件，保持在现有页面内完成，不新增无意义抽象。
- 修改 `web/src/styles.css` 或聊天室局部样式文件，保证实时状态、断线提示和消息列表布局不破坏现有桌面/移动端。

**实现要求：**

- 页面挂载时先加载房间和历史消息，再建立 WebSocket；连接 READY 后停止默认 3 秒聊天室轮询。
- WebSocket 进入 `degraded` 时才启用现有 REST after-sequence 轮询；恢复 READY 后停止轮询，避免双重消息流。
- 保留游戏当前状态和倒计时原有 1 秒轮询；只移除或替换聊天室专属轮询，不能影响游戏业务。
- 所有实时消息继续经过 `mergeChatMessages` 或等价的单一去重排序函数，按消息 ID/序列去重并保持滚动策略。
- 发送按钮在 pending 时防止重复提交；REST 降级发送仍使用 `sendChatMessage`，不绕过服务端幂等。
- 展示连接状态、未读数量和发送失败状态时使用简洁中文，不显示 ticket、token、内部错误、数据库错误或技术堆栈。
- 切换页面、退出登录、组件卸载必须调用 socket cleanup；重复挂载不能留下旧连接或旧轮询。
- 消息内容按纯文本渲染；禁止 `v-html`、字符串 HTML 拼接或 `eval`。

**验证：**

- 375px 宽度下输入区、发送按钮和消息内容不横向溢出。
- 桌面宽度下长消息、快速连续消息、空消息和发送失败状态布局稳定。
- 页面刷新后历史消息和新消息顺序正确；断网恢复后不重复。
- 前端 typecheck/build 通过。通过后提交：`feat: 将用户聊天室切换为实时优先`。

### Task 8：补齐配置、反向代理和安全运行说明

**目标：** 让本地和服务器都能按同一契约正确转发 WebSocket，并保留可控的 Origin、连接和超时配置。

**文件：**

- 修改 `server/xupan-server/src/main/resources/application.yml` 和测试 profile，补齐 `xupan.chat.websocket` 配置说明。
- 修改 `server/xupan-server/src/main/java/com/xupan/server/config/SecurityConfig.java`，检查 WebSocket 握手路径、静态前端路径、REST CSRF/CORS 和权限规则的一致性。
- 新增或修改 `docs/部署运行说明.md` 中的 Nginx WebSocket location 示例；若该文件不存在，创建同级中文文档。
- 修改 `README.md` 或现有运行说明，记录本地 WS 地址、前端代理、配置项来源和排查方法。

**Nginx 要求：**

```nginx
location /ws/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 75s;
}
```

实施时必须结合现有服务器配置确认 `proxy_pass`、域名、TLS、静态前端和 `/api/` 的实际路径，不能直接复制片段覆盖生产配置。生产 Origin、连接上限和超时由环境配置提供；敏感信息仍只放 `docs/本地环境密钥.md` 或服务器受控配置。

**验证：**

- 本地 Vite 代理和 Spring Boot 直接访问均能完成握手。
- Nginx 配置通过 `nginx -t`，重载前确认现有站点配置没有被覆盖。
- 检查浏览器 Network 的 WS 状态码、Upgrade、Origin 和关闭原因；日志中没有 ticket 原文。

通过后提交：`docs: 补充聊天室 WebSocket 运行配置`。

### Task 9：执行自动化测试和安全静态检查

**目标：** 证明实时流没有破坏已经完成的认证、聊天室 REST 和前端构建。

**后端验证矩阵：**

```powershell
Set-Location D:\codes\liuhecai\xupan-platform\server\xupan-server
.\mvnw.cmd test
.\mvnw.cmd -Dspring.profiles.active=test -Dtest="*Chat*" test
```

- H2 全量测试必须通过；报告中区分测试数据库和 MySQL 验证。
- 使用当前项目约定的 MySQL 8.4.11 测试配置执行聊天室专项迁移、ticket、REST、WebSocket 集成测试；不能把 H2 通过当作 MySQL 通过。
- 检查 Flyway 迁移与当前数据库版本、JSON 字段、索引、锁和时间精度兼容。

**前端验证矩阵：**

```powershell
Set-Location D:\codes\liuhecai\xupan-platform\web
npm run typecheck
npm run build
```

**静态安全检查：**

- 搜索 WebSocket URL、日志、异常和浏览器存储，确认没有 access token、refresh token 或 ticket 持久化/打印。
- 搜索聊天室渲染路径，确认没有 `v-html`、`innerHTML`、`eval` 或等价的不受控 HTML 执行。
- 检查 WebSocket 入站字段不能覆盖 `userId`、昵称、头像、权限和房间主体。
- 检查所有新 Controller/service 方法都有当前项目已有的认证、权限、参数校验和统一异常处理。

通过后提交：`test: 验证聊天室实时链路与安全边界`。

### Task 10：双浏览器人工验收和验收记录

**目标：** 用真实浏览器证明“正式聊天窗口”可用，而不是只证明单元测试通过。

**准备：**

- 通过现有后台用户管理创建或确认两个普通用户 A、B；分别授予聊天室查看和发言权限；不要在文档中记录真实密码。
- 确认 A、B 都能登录用户端，进入同一个聊天室，测试环境使用独立数据库或明确的测试数据。

**验收步骤：**

1. A、B 同时进入房间，记录 WebSocket 连接为 101，确认房间和用户主体正确。
2. A 发送短文本、长文本边界和连续两条消息；B 验证实时到达、顺序、发送者快照和纯文本显示。
3. B 回复；A 验证发送端 ACK 不造成重复消息。
4. 在发送前断开 B 网络，A 继续发送；恢复 B 网络，验证 ticket 重取、重连和 after-sequence 补偿无遗漏、无重复。
5. 刷新页面、切换房间、退出登录，确认旧连接关闭、无后台重连、消息历史仍正确。
6. 禁用 B、撤销 B 发言权限或禁言 B，验证 B 不能发送；恢复权限后重新登录/重连才恢复。
7. 让 WebSocket 失败或临时停用代理，验证页面进入降级状态、REST 仍能发消息，恢复后轮询停止。
8. 使用 375px 移动宽度和桌面宽度检查输入区、消息列表、滚动和状态提示。

**记录：**

- 新增 `docs/验收记录/2026-09-11-聊天室实时消息验收记录.md`，填写环境、代码提交、浏览器、测试账号标识（不得写密码）、步骤、结果、失败证据和未执行项。
- 记录浏览器 Network、服务端日志、测试命令和数据库查询的关键结果；截图和日志不得包含 token、ticket、密码或其他敏感值。

通过后提交：`docs: 记录聊天室实时消息人工验收`。

### Task 11：构建、发布和回滚验证

**目标：** 让本阶段能够按当前服务器运行方式安全发布，并且失败时可回到上一版本。

**执行顺序：**

1. 检查 `git status`、当前提交、构建产物和敏感文件忽略状态。
2. 本地执行后端测试、前端构建和前端静态产物检查。
3. 发布前记录服务器当前 commit、服务状态、Java 版本、数据库迁移版本和 Nginx 配置摘要。
4. 以可回滚方式上传当前版本；数据库本阶段没有预期新增迁移，仍须确认 Flyway 状态。
5. 重启 Spring Boot 前确认没有其他用户正在进行人工验收；启动后检查健康端点、认证、WS 101 握手和双浏览器流程。
6. 修改 Nginx 前保存原配置，执行 `nginx -t` 后再 reload；失败时恢复原配置并重新验证。
7. 发布失败时恢复上一版本应用和配置，不删除数据库历史数据；记录失败原因和回滚结果。

**文档：**

- 更新 `docs/项目方案.md` 的阶段状态，把“WebSocket 实时消息”从未实现改为已验证或保留具体未完成项。
- 更新 `docs/项目记忆.md` 的当前运行基线、端口和部署状态；不写入密码、token、完整会话 URL。
- 更新 `README.md` 的启动、测试、WS 代理和人工验收入口。

通过后提交：`release: 完成单实例聊天室实时消息阶段`。

## 5. 后续阶段交接边界

### 5.1 交给机器人阶段的接口

阶段 5 只能在本计划验收通过后开始。机器人发送必须经过明确的机器人领域服务，例如 `RobotMessageService`，最终调用与用户消息等价的服务端消息写入和事件发布管道。机器人身份、模板、触发条件、频率限制、审核/撤回、审计和后台权限应独立建模，不能让客户端发送 `robot` 事件或直接调用内部广播端口。

机器人阶段需要重新形成独立开发计划，至少覆盖：机器人用户/身份、消息模板、启停和房间范围、触发调度、幂等、审计、权限、限流、异常重试和双浏览器验收。本阶段不提前创建这些表和接口。

### 5.2 交给多实例阶段的接口

本阶段的 `ChatRealtimeBroadcaster` 是进程内接口；多实例时替换其发布实现，连接注册表仍只保留本机连接。可靠顺序和重试必须围绕 `chat_outbox` 设计，不能把数据库事务提交后的本机事件误认为跨实例可靠投递。

## 6. 验收命令清单

在 `D:\codes\liuhecai\xupan-platform` 根目录执行：

```powershell
git status --short
git diff --check

Set-Location server/xupan-server
.\mvnw.cmd test
.\mvnw.cmd -Dspring.profiles.active=test -Dtest="*Chat*" test

Set-Location ../../web
npm run typecheck
npm run build
```

人工验收前后必须再次执行 `git status --short`，确认没有生成日志、`target/`、浏览器导出文件、本地密钥或其他未计划文件。若使用 MySQL 8.4.11，必须把实际 JDBC 目标、Flyway 版本和专项测试结果写入验收记录。

## 7. 参考文件

- 总体方案：`docs/plans/2026-09-10-聊天室与常驻机器人改造方案.md`
- 已完成 REST 纵向切片计划：`docs/plans/2026-09-10-聊天室消息持久化与REST纵向切片开发计划.md`
- 已完成用户系统/权限计划：`docs/plans/2026-09-10-用户系统与权限基础开发计划.md`
- 已完成用户管理最小可用后台计划：`docs/plans/2026-09-11-用户管理最小可用后台开发计划.md`
- 当前项目方案：`docs/项目方案.md`
- legacy 参考审计：`docs/参考资料/caipiaowan-legacy-参考.md`
- legacy Workerman：`D:\codes\liuhecai\_caipiaowan_extract\caipiaowan-main\Application\Home\Controller\WorkermanlhcController.class.php`
- legacy Socket.IO/内部发布：`D:\codes\liuhecai\_caipiaowan_extract\caipiaowan-main\start_io.php`
- legacy 机器人后台：`D:\codes\liuhecai\_caipiaowan_extract\caipiaowan-main\Application\Admin\Controller\RobotController.class.php`
- legacy 聊天客户端：`D:\codes\liuhecai\_caipiaowan_extract\caipiaowan-main\Template\Home\Run\js\lot_im.html`
- 当前认证 ticket：`server/xupan-server/src/main/java/com/xupan/server/auth/service/TokenService.java`
- 当前聊天 REST：`server/xupan-server/src/main/java/com/xupan/server/chat/web/ChatController.java`
- 当前消息服务：`server/xupan-server/src/main/java/com/xupan/server/chat/service/ChatMessageService.java`
- 当前用户端聊天室：`web/src/views/UserRoom.vue`

## 8. 执行状态

执行记录（2026-09-11）：

- [x] 已核对总体方案、已完成阶段和当前代码边界。
- [x] 已核对 legacy 聊天、WebSocket、Socket.IO、内部发布和机器人源码，并冻结可借鉴/禁止迁移项。
- [x] 已冻结本阶段协议、认证、状态机、补偿和降级设计。
- [x] Task 1-5：后端依赖、协议、ticket 握手、连接治理、提交后广播、订阅、消息 ACK、读游标和限流已实现。
- [x] Task 6-7：前端 typed socket、重连、after-sequence 补偿、REST 降级和 UserRoom 实时优先接入已实现。
- [ ] Task 6 自动化 Mock WebSocket 测试：当前 `web/package.json` 尚未配置 Vitest/Jest 测试运行器，本轮未把 typecheck/build 结果冒充为前端行为测试；需在补充前端测试基础设施后执行。
- [x] Task 8：应用配置、Vite `/ws` 代理、安全 Origin 白名单和部署说明已补齐；真实 Nginx 配置尚未在服务器执行。
- [x] Task 9：后端全量 H2 `128/128`、编译、实时专项测试、前端 typecheck/build 已通过。
- [ ] Task 9：MySQL 8.4.11 运行态专项验证仍待有效本地数据库凭据。
- [ ] Task 10：双浏览器人工验收与断线补偿记录。
- [ ] Task 11：服务器发布、Nginx reload、回滚演练和最终阶段结项。
