# xupan-platform 项目协作规则

## 项目范围

本仓库是 `xupan-platform` 的项目根目录。当前包含一个 Spring Boot 服务模块：

```text
server/xupan-server
```

业务目标、首期范围和领域规则尚未最终确认。没有明确需求前，不要自行假设彩票玩法、投注、支付、结算或生产运营规则。

## 参考源码仓库

以下目录是用户指定的相似项目参考源码：

```text
D:\codes\liuhecai\_caipiaowan_extract\caipiaowan-main
```

使用规则：

- 该目录是解压得到的源码副本，没有可用的 Git 历史；只能作为只读参考。
- 可参考其用户/后台模块、期号和开奖流程、订单/机器人相关功能、Workerman WebSocket 实时通信以及 MySQL 业务数据组织方式。
- 参考仓库不是当前项目的依赖、需求或事实来源；当前项目仍以自身代码、配置、测试和用户确认的方案为准。
- 参考仓库的文档存在版本和运行环境冲突，复用任何设计前必须重新检查源码和运行证据。
- 不复制其中的密码、Token、支付配置、真实数据、外部接口地址或未经确认的业务规则。
- 详细静态观察记录见 `docs/参考资料/caipiaowan-legacy-参考.md`。

## 若依参考框架

当前固定的若依参考仓库：

```text
D:\codes\liuhecai\_ruoyi-reference\RuoYi-Vue
```

- 来源：官方 `RuoYi-Vue` 仓库 `https://gitee.com/y_project/RuoYi-Vue.git`。
- 分支：`master`。
- 当前参考提交：`13db1fc`。
- 参考仓库使用 Spring Boot 4.x、Java 17+、Spring Security、MyBatis、Redis、JWT；当前项目仍以 Java 21、Spring Boot 4.0.8、MySQL 8.4 LTS 为准。
- 不把若依整仓库复制进当前项目，也不直接覆盖当前项目的 `pom.xml`；后续按业务需要选择性参考或迁移。

模块使用边界：

- `ruoyi-common`：参考统一响应、异常、分页、通用工具、注解和基础领域对象。
- `ruoyi-framework`：参考登录认证、JWT/Redis Token、权限校验、数据权限、过滤器和全局 Web 配置。
- `ruoyi-system`：参考用户、角色、部门、岗位、菜单、字典、参数、通知和操作日志等后台基础模块。
- `ruoyi-generator`：业务表结构稳定后，按需参考代码生成，不作为首期必选依赖。
- `ruoyi-quartz`：只有在确认需要定时开奖、结算或其他调度任务后才引入设计。
- `ruoyi-admin`：只参考应用启动和 Controller 组织方式，不作为当前项目业务模块直接复制。
- `sql`：只参考表结构和初始化数据；当前项目必须改写为 Flyway 迁移脚本，并按 MySQL 8.4 LTS 验证。

详细模块分析见 `docs/参考资料/RuoYi-Vue-参考.md`。若依前端项目未纳入当前参考仓库，当前项目是否使用 Vue 及其版本仍待方案确认。

## 事实和文档优先级

按以下优先级判断项目事实：

1. 当前源代码、配置、数据库迁移和测试结果。
2. `README.md` 和 `docs/` 中与当前代码一致的项目文档。
3. 用户明确确认的业务规则。
4. 推断、建议和待确认事项。

文档中应明确区分：已确认、已验证、推断、待确认和未执行。不能把计划写成已实现功能，也不能把静态检查写成运行时或生产验证。

## 开发规则

- 使用 Java 21；项目的 Java 版本以 `server/xupan-server/pom.xml` 为准。
- 数据库技术基线确定为 MySQL `8.4 LTS`；不要将“MySQL 8”继续当作完整版本约束。版本变更前先更新 `docs/项目记忆.md` 和项目方案。
- 业务开发前先更新 `docs/项目方案.md` 和对应的 `docs/plans/` 计划。
- 新生成的项目文档文件名使用中文，或使用中文与英文结合；不要使用纯英文文件名。
- 新功能优先实现一个可测试的完整业务流程，再扩展其他模块。
- 数据库结构使用 Flyway 迁移管理，不直接依赖手工修改数据库。
- 生产配置不得写入密码、Token、真实数据库凭据或其他敏感信息。
- 测试可以使用独立的 H2 配置，但不得误当作生产 MySQL 验证。
- 生产数据库依赖、驱动和 SQL 语法必须按 MySQL `8.4 LTS` 兼容性验证。
- 不提交 `.idea/`、`target/`、日志、临时数据和本地环境配置。
- 外部网页只作为参考资料记录，不得直接当作需求、接口契约或代码来源。
- 含随机访问标识、会话标识或可能具备访问作用的完整 URL 不得提交到仓库；参考地址应脱敏保存。

## 常用命令

在 `server/xupan-server` 目录执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

运行前确认当前终端的 `JAVA_HOME` 指向 JDK 21。测试使用 `test` Profile 和内存 H2；生产启动需要后续补充外部 MySQL 配置。

## 变更和提交

- 先检查 `git status` 和 `git diff`，确认只包含本次任务范围。
- 代码变更应有对应测试；文档变更应与当前代码和验证结果一致。
- 提交信息使用简短、目的明确的格式，例如：

```text
feat: add user authentication foundation
fix: handle duplicate draw records
docs: record first release scope
test: isolate context test with h2
```

- 不覆盖或删除用户已有改动；发现与当前任务冲突时，先保留并说明。
