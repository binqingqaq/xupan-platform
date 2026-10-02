# BY220 平移项目：DeepSeek 桌面端 Agent 交接

> 交接日期：2026-10-02
>
> 当前分支：`main`
>
> 当前代码基线：`b69881f feat: 增加 BY220 代理后台玩家明细`
>
> Git 状态：交接前工作区干净；`main` 领先 `origin/main` 10 个提交。
>
> 当前数据库基线：本机 MySQL 8.4.11 Flyway V54。
>
> 当前验证：后端 H2/Flyway `320/320`、前端 Vitest `126/126`、`typecheck`、生产构建、本机 18080 健康检查。服务器和生产未验证。
>
> 本文是后续 DeepSeek 桌面端 Agent 的当前进度和未完成目标入口。它不替代用户确认的业务规则；发生冲突时先看当前源码、迁移、测试和 [BY220 严格平移总规则](../参考资料/BY220严格平移总规则.md)。

## 1. 项目目标

`xupan-platform` 的目标不是重新设计一个相似产品，而是用现代技术栈重建 BY220 的同业务系统：

```text
BY220 业务与界面
  -> 保持业务流程、菜单、字段、按钮、权限和状态流转基本一致
  -> 替换为 Java 21、Spring Boot、Vue 3、TypeScript、MySQL 8.4 LTS、Flyway
```

第一参考项目：

```text
D:\codes\liuhecai\_github-by220-cp
```

当前项目：

```text
D:\codes\liuhecai\xupan-platform
```

强制规则：

1. 先读 BY220 控制器、模板、JavaScript、SQL、字段和状态流转。
2. 明确 BY220 的菜单、按钮、输入字段、结果、异常和状态变化。
3. 再设计 xupan 的数据库、API、权限、页面、测试和 Flyway。
4. 遇到不了解的行为，继续看 BY220 和运行结果，不要凭经验发明业务。
5. 必要的合规或安全偏差必须记录并让用户确认。
6. 不得复制 BY220 的密码、Token、真实配置、外部地址或敏感数据。
7. 所有余额、下注、结算和盈亏都只表示虚拟积分，不接入现实资金。

## 2. 三个业务面必须分离

| 业务面 | BY220 | xupan 正式入口 | 当前状态 |
| --- | --- | --- | --- |
| 玩家前台/下注端 | 玩家链接、期号、开奖、聊天、下注 | `/room` | 已有玩家链接、聊天下注、快捷下注、开奖和多彩种基础；仍需继续按 BY220 玩家端逐项核对 |
| 代理后台 | `robot.php` | `/agent` | 已有创建普通玩家/托、上下分、链接管理、运营汇总和玩家注单/流水明细 |
| 超级管理后台 | `admin.php` | `/platform-admin` | 第一轮完整菜单已建立独立页面/API；仍需逐字段、按钮和异常分支深化 |
| 现有运营工作台 | 非 BY220 正式替代 | `/console` | 已有玩家工作台、上下分审批、下注榜、赔率和限额等；可保留，但不得冒充 BY220 超级后台 |
| 临时内部辅助页 | 无正式对应 | `/console/agents` | 仅临时兼容；不是 BY220 菜单，不得继续扩张为正式业务入口 |

三个业务面不得互相替代：

- 玩家前台不是代理后台。
- 代理后台不是超级管理后台。
- 超级管理后台不能代替代理创建玩家和托。
- `/console/agents` 必须保持“临时内部辅助”定位，后续删除或隐藏；正式菜单是 `/platform-admin/sub-accounts` 和 `/platform-admin/machines`。

## 3. 当前 Git、运行和验收基线

交接前确认：

```text
分支：main
代码：b69881f
origin/main：落后当前分支 10 个提交
工作区：干净
数据库：本机 MySQL 8.4.11，Flyway V54
运行：http://127.0.0.1:18080
/actuator/health：200
/agent：200
```

当前完整证据：

| 检查 | 当前结果 |
| --- | --- |
| 后端 H2/Flyway | `320/320` |
| 前端 Vitest | `126/126` |
| 前端 typecheck | 通过 |
| 前端生产构建 | 通过 |
| 本机 MySQL | V54 迁移和关键运行态通过 |
| 服务器 | 仅历史 `caf4677` V13→V32 证据；V54 未部署验证 |
| 生产 | 未验证 |

如果接手时工作区、提交号或测试结果已经变化，先把最新结果写回本文和 [模块状态矩阵](../模块状态矩阵.md)，不要沿用旧数字。

## 4. 已完成能力

### 4.1 超级管理后台 `/platform-admin`

已形成独立页面/API的 BY220 菜单：

- 开奖信息
- 子账号
- 机器管理
- 报表统计
- 开奖历史
- 未结订单
- 订单改单
- 在线玩家
- 设置
- 网盘设置
- 游戏设置
- 修改密码
- 安全退出

已完成的重点：

- `/platform-admin/login` 独立登录和路由权限。
- 子账号独立登录、失效时间拦截、机器/报表范围隔离、动态机器权限、依赖保护软删除。
- 机器管理、玩家/托查询、状态和删除流程。
- 报表统计的积分流水、盈亏、单边/双边流水和返水。
- 开奖历史彩种选择、单期/全部强制结算、手动补期。
- 未结订单安全撤单和虚拟积分返还。
- 订单改单 1 至 8 球、钱包差额流水、幂等和审计。
- 在线玩家列表、下线、持久化私信和玩家轮询。
- 设置读写、公开设置、清空数据预检/事务删除、删除全量账号预检/软删除。
- 网盘设置的内部增删改和状态切换，不接外部网盘。
- 游戏设置多彩种 CRUD、A-D 盘赔率、关闭状态影响新下注。
- 修改密码三字段流程、旧会话失效和审计。
- 自动轮期遍历所有启用彩种；开奖信息/历史支持 `gameCode`。

仍明确保留的未完成项：

- 旧演示人工开奖/重置接口仍默认 AU8。
- WebSocket 下注协议尚未携带 `gameCode`，非 AU8 玩家前台走 REST fallback。
- 外部开奖源和外部网盘未接入。
- 部分 BY220 字段和异常分支仍需运行态逐项对照。
- 清空数据破坏性执行的正式库提交验证未做；目前本机 MySQL 验证过预检和事务回滚，H2 验证完整删除结果。
- 服务器 V54 迁移、HTTPS、Secure Cookie、生产浏览器和长期运行未验证。

### 4.2 代理后台 `/agent`

已实现：

- 代理账号密码登录、`AGENT_CONSOLE_READ` 和 `AGENT_PLAYER_MANAGE` 权限。
- 创建普通玩家和托，自动归属当前代理。
- 创建后签发 `PLAYER_FULL` 玩家链接。
- 托数量受 `agent.bot_count` 限制。
- 普通玩家上分：扣代理积分、加玩家钱包。
- 普通玩家下分：反向处理。
- 托上下分：只调整玩家钱包，不改变代理积分。
- 使用 `demo_balance_ledger`、幂等键和钱包服务。
- 玩家链接查看、刷新、拉黑、恢复，并校验 `agent_id` 归属。
- 运营汇总：业务日下注笔数、流水、净盈亏、待结算、活跃玩家、普通/BOT 流水。
- 玩家明细：注单和积分流水分页。
- 代理停用同步账号状态、会话版本和审计。

重要区分：

- 超级管理员原有玩家链接管理仍然存在，作用于全局玩家。
- `/agent` 链接管理是 BY220 `robot.php` 对应的代理范围入口；两者复用同一链接事实和 `player_access_link` 数据，但权限和数据范围不同。
- 代理直接上下分不等于玩家聊天上下分申请审批，两套业务并存。
- 只读明细查询不能使用 `SELECT ... FOR UPDATE`；MySQL 只读事务会报错。当前已改为只读非锁定归属校验。

## 5. 当前未完成目标（按顺序）

除非用户明确改变优先级，后续按以下顺序继续。报网、盘口、中心上游和二层结算仍暂缓。

### P0：代理赔率、返水、封盘、取消的真实结算映射

这是当前最优先的业务缺口，也是最容易做错的部分。

先读 BY220：

```text
D:\codes\liuhecai\_github-by220-cp\application\rbt\controller\Index.php
```

重点方法：

```text
UpdatePeiLv
UpdatePeiLv2
UpdateFanShui
UpdateFanShui2
UpdateFengpan
UpdateCancel
```

同时读：

```text
D:\codes\liuhecai\_github-by220-cp\application\common.php
```

重点确认：

- `peilv` 的计算和符号。
- `tePeilv` 的计算和符号。
- 普通玩法和特码玩法的差异。
- 返水基数、比例和快照时机。
- 封盘、取消、撤单和退款对注单/钱包/结算状态的影响。
- 代理配置是仅影响新注单，还是也影响修改/重结算。
- 幂等、重复提交、并发修改和审计要求。

当前 xupan 相关位置：

```text
server\xupan-server\src\main\java\com\xupan\server\game\service\SettlementService.java
server\xupan-server\src\main\java\com\xupan\server\game\service\DemoGameService.java
server\xupan-server\src\main\java\com\xupan\server\agent\service\AgentConsoleService.java
server\xupan-server\src\main\java\com\xupan\server\agent\web\AgentConsoleController.java
game_bet.odds_snapshot
game_bet.rebate_rate_snapshot
game_bet.special_rebate_rate_snapshot
game_odds
agent.odds_rate
agent.special_odds_rate
```

硬性要求：

1. 先给出 BY220 公式到 xupan 字段/服务/状态的映射。
2. 明确哪些配置在下注时快照，哪些在结算时读取。
3. 先补结算集成测试和本机 MySQL 验证，再补页面。
4. 未证明已经影响下注/结算前，禁止只做一个“能保存但无业务效果”的赔率页面。
5. 不得自行改动 BY220 的公式、方向和状态流转。

### P1：代理筛选、导出、钱包和转移

完成 P0 后再做：

- 日期、期号、玩家、普通/托、状态、玩法等筛选。
- 报表分页、区间统计和导出。
- 代理钱包、信用、冻结和可用额度。
- 玩家在平台直属代理/普通代理之间的转移，及审计、幂等和归属校验。
- 代理聊天室和托自动行为的代理范围可见性。

必须先确认 BY220 是否有同字段、同按钮和同状态；没有确认的功能不能自行发明。

### P2：WebSocket 下注 gameCode

- 当前非 AU8 玩家前台走 REST fallback。
- WebSocket 消息必须携带并校验 `gameCode`，并保持期号和彩种隔离。
- 需要补协议测试、跨彩种隔离测试和断线重连测试。
- 不得删除 REST fallback，除非替代链路已经完整验证。

### P3：旧演示人工开奖/重置彩种化

- 旧 `/api/demo/game/admin/draw` 和 `/api/demo/game/admin/reset` 仍默认 AU8。
- 先把请求、服务、期号、事件和审计全部带入 `gameCode`。
- 保留旧接口的安全边界，不把它误称为 BY220 正式超级后台菜单。

### P4：玩家前台与 BY220 玩家端差异审计

按页面和操作逐项比对：

- 链接进入。
- 期号/彩种选择。
- 开奖和路/字/图。
- 聊天和系统消息。
- 下注、撤单、余额和流水。
- 封盘、取消、反馈和异常分支。
- BY220 有而 xupan 缺失的按钮、字段和状态。

### 暂缓项

在用户明确解除前，不要先做：

- 报网。
- 盘口。
- 中心上游。
- 二层结算。
- 现实资金、充值、提现、支付和可兑换资产。

## 6. 当前代码入口

代理后台：

```text
server\xupan-server\src\main\java\com\xupan\server\agent\service\AgentConsoleService.java
server\xupan-server\src\main\java\com\xupan\server\agent\web\AgentConsoleController.java
server\xupan-server\src\main\java\com\xupan\server\agent\web\AgentAdminController.java
web\src\views\AgentConsole.vue
web\src\agentConsole.test.ts
```

超级管理后台：

```text
server\xupan-server\src\main\java\com\xupan\server\platformadmin\
web\src\views\PlatformAdmin*.vue
web\src\components\platform-admin\PlatformAdminNav.vue
```

玩家链接：

```text
server\xupan-server\src\main\java\com\xupan\server\playerauth\service\PlayerLinkAuthenticationService.java
```

游戏、下注和结算：

```text
server\xupan-server\src\main\java\com\xupan\server\game\service\DemoGameService.java
server\xupan-server\src\main\java\com\xupan\server\game\service\SettlementService.java
server\xupan-server\src\main\java\com\xupan\server\game\service\自动轮期服务.java
server\xupan-server\src\main\java\com\xupan\server\game\repository\GameDataRepository.java
```

数据库迁移：

```text
server\xupan-server\src\main\resources\db\migration\V1...V54
```

当前最新：

```text
V54__增加代理玩家管理权限.sql
```

## 7. 开发与验证命令

先确认工作区：

```powershell
git status --short --branch
git log -1 --oneline
git rev-list --count origin/main..HEAD
```

后端：

```powershell
cd D:\codes\liuhecai\xupan-platform\server\xupan-server
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
cd D:\codes\liuhecai\xupan-platform\web
npm install
npm run test:run
npm run typecheck
npm run build
npm run dev
```

本机服务：

```text
http://127.0.0.1:18080
```

本机数据库应用账号：

- 只从 `docs/本地环境密钥.md` 的“应用账号”章节读取 `xupan_app` 对应用户名和密码。
- 不得把密码、Token、真实地址复制到普通文档、聊天、日志或 Git。
- 不得按文件中第一个“密码”字段取值。

## 8. 接手时的必做检查

1. 先读本交接文档、[BY220 严格平移总规则](../参考资料/BY220严格平移总规则.md)、[模块状态矩阵](../模块状态矩阵.md) 和 [09 代理组织与代理后台](../业务模块/09-代理组织与代理后台.md)。
2. 核对当前 Git、Flyway 和测试结果是否仍与本文一致。
3. 读 BY220 对应控制器、模板、JavaScript、SQL 和状态流转。
4. 把功能映射写进对应的 `docs/plans/` 专项计划，再修改代码。
5. 修改后补自动化测试、本机 MySQL 验证和验收记录。
6. 未执行的服务器、生产、外部上游或浏览器检查必须写“未验证”。

## 9. 测试和环境边界

- H2 只证明领域逻辑和测试上下文。
- 本机 MySQL 只证明本机迁移和接口链路。
- 服务器现场和生产验证必须单独执行。
- 不得用 H2 结果写成生产通过。
- 不得用静态页面、假数据或前端状态冒充业务完成。
- 数据库是业务事实源；前端状态、浏览器状态和临时文件不能成为唯一事实。
- 所有变更都要考虑权限、事务、幂等、并发、审计、失败恢复和敏感信息脱敏。

## 10. 已知风险

- 代理赔率保存不等于结算生效。
- 只读事务执行 `SELECT ... FOR UPDATE` 已在 MySQL 暴露过问题。
- 非 AU8 WebSocket 下注尚未通关，当前必须保留 REST fallback。
- `/console/agents` 不是正式 BY220 超级后台。
- 旧演示开奖/重置接口不是正式超级后台菜单。
- 清空数据和删除全量账号是破坏性操作，必须保留预检、确认文本、事务、审计和测试。
- 服务器仍在历史 `caf4677` 基线，当前 V54 未部署验证。
- 所有金额仍只表示虚拟积分，不接入现实资金。

## 11. 完成判定

只有同时满足以下条件，才能把某个 BY220 页面或流程标记完成：

1. 菜单名称和位置一致。
2. 页面主要结构一致。
3. 操作按钮和流程一致。
4. 字段含义一致。
5. 数据结果和状态变化一致。
6. 权限边界一致。
7. 必要偏差已经记录并确认。
8. 自动化测试、本机运行态和对应验收记录已完成。
9. 未执行的服务器/生产检查明确标记。