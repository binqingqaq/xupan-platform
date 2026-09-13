# xupan-platform

`xupan-platform` 是一个正在建立中的平台项目。当前首期目标是完成 8 球番摊玩法演示，后端先提供可控赔率和开奖演示接口。

## 当前状态

- 后端模块：`server/xupan-server`
- 技术基线：Spring Boot `4.0.8`、Java `21`
- 构建方式：Maven Wrapper
- 生产数据库：MySQL `8.4 LTS` + Flyway
- 测试数据库：测试 Profile 使用内存 H2
- 当前已具备：玩法领域模型、结算服务、Flyway/MySQL 持久化、赔率控制/下注/开奖 API、认证授权、虚拟余额管理员分配、聊天室 REST 持久化，以及单实例 WebSocket 实时消息链路代码
- 当前阶段已验证：独立 MySQL 8.4.11 运行态、单实例 WebSocket、Nginx Upgrade、服务器发布回滚，以及本地两个 Playwright 隔离浏览器上下文的实时收发
- 当前仍待补齐：页面刷新恢复和浏览器断网重连；常驻机器人、Redis 多实例、聊天治理后台和正式生产高可用仍未完成
- 当前余额能力：仅支持 `DEMO-USER` 虚拟演示余额和可审计余额流水，不代表真实资金系统

## 环境要求

- JDK 21
- 可访问 Maven Central 的网络，或已准备好 Maven 本地依赖缓存
- 生产运行阶段需要 MySQL `8.4 LTS`；当前仓库没有提交任何数据库账号或密码

检查 Java 版本：

```powershell
java -version
```

如果当前终端未配置 `JAVA_HOME`，请先将它设置为本机 JDK 21 安装目录：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 构建和测试

进入后端模块目录：

```powershell
cd server\xupan-server
```

运行测试：

```powershell
.\mvnw.cmd test
```

当前完整测试套件使用 `src/test/resources/application-test.yaml` 中的内存 H2 和 Flyway，最近一次通过 135 个测试。前端 Vitest 最近一次通过 11 个测试；这些测试和 H2 均不替代真实 MySQL 验证。

## 启动服务

```powershell
cd server\xupan-server
.\mvnw.cmd spring-boot:run
```

默认 `application.yaml` 使用 `XUPAN_DB_URL`、`XUPAN_DB_USERNAME`、`XUPAN_DB_PASSWORD` 注入 MySQL 连接；启动前必须确认本机环境变量或受控配置已提供凭据。启动后访问 `http://127.0.0.1:8080/` 可打开首期演示页面；用户聊天室为 `/room`，实时地址为 `/ws/chat/{roomCode}`，浏览器 WebSocket ticket 由 `/api/auth/ws-ticket` 短期签发。

## 前端开发

进入 `web` 目录后运行：

```powershell
npm install
npm run dev
```

生产构建使用 `npm run build`，构建产物会写入 Spring Boot 的静态资源目录；类型检查使用 `npm run typecheck`。用户前台地址为 `/room`，演示后台地址为 `/admin`。

## 项目文档

- [项目协作规则](AGENTS.md)
- [项目记忆](docs/项目记忆.md)
- [环境准备](docs/环境准备.md)
- [聊天室 WebSocket 部署运行说明](docs/部署运行说明.md)
- [单实例聊天室运行态验收记录](docs/验收记录/2026-09-13-单实例聊天室运行态验收记录.md)
- [首期项目方案](docs/项目方案.md)
- [当前需求基线：汇博盈页面复刻](docs/需求基线/2026-09-10-汇博盈页面复刻.md)
- [首期实施计划](docs/plans/2026-09-08-首期玩法演示.md)
- [汇博盈业务参考](docs/参考资料/汇博盈-业务参考.md)
- 项目方案：已建立，玩法金额口径仍保留待确认项
- 实施计划：已建立在 `docs/plans/`

## 当前开发入口

开始业务开发前，需要先阅读当前需求基线，并确认：

1. 目标用户和首期核心业务流程。
2. 首期包含和明确不包含的功能。
3. 用户、角色、权限和数据边界。
4. 首期需要落库的数据及其生命周期。
5. 可执行的验收条件。

确认后，应先形成项目方案和首期实施计划，再修改业务代码。
