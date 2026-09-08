# xupan-platform

`xupan-platform` 是一个正在建立中的平台项目。当前首期目标是完成 8 球番摊玩法演示，后端先提供可控赔率和开奖演示接口。

## 当前状态

- 后端模块：`server/xupan-server`
- 技术基线：Spring Boot `4.0.8`、Java `21`
- 构建方式：Maven Wrapper
- 生产数据库：MySQL `8.4 LTS` + Flyway
- 测试数据库：测试 Profile 使用内存 H2
- 当前已具备：玩法领域模型、结算服务、Flyway/MySQL 持久化、赔率控制/下注/开奖 API、原生静态演示页面
- 当前开发中：首期四类玩法最终验收和验收文档收口
- 当前尚未具备：认证授权、自动开奖、实时通信、生产部署和真实运营能力

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

当前完整测试套件使用 `src/test/resources/application-test.yaml` 中的内存 H2 和 Flyway，已通过 11 个测试。它不替代真实 MySQL 验证。

## 启动服务

```powershell
cd server\xupan-server
.\mvnw.cmd spring-boot:run
```

默认 `application.yaml` 使用 `XUPAN_DB_URL`、`XUPAN_DB_USERNAME`、`XUPAN_DB_PASSWORD` 注入 MySQL 连接；本机开发环境已配置完成。启动后访问 `http://127.0.0.1:8080/` 可打开首期演示页面。

## 项目文档

- [项目协作规则](AGENTS.md)
- [项目记忆](docs/项目记忆.md)
- [环境准备](docs/环境准备.md)
- [首期项目方案](docs/项目方案.md)
- [首期实施计划](docs/plans/2026-09-08-首期玩法演示.md)
- [汇博盈业务参考](docs/参考资料/汇博盈-业务参考.md)
- 项目方案：已建立，玩法金额口径仍保留待确认项
- 实施计划：已建立在 `docs/plans/`

## 当前开发入口

开始业务开发前，需要先确认：

1. 目标用户和首期核心业务流程。
2. 首期包含和明确不包含的功能。
3. 用户、角色、权限和数据边界。
4. 首期需要落库的数据及其生命周期。
5. 可执行的验收条件。

确认后，应先形成项目方案和首期实施计划，再修改业务代码。
