# xupan-platform 项目协作规则

## 项目范围

本仓库是 `xupan-platform` 的项目根目录。当前包含一个 Spring Boot 服务模块：

```text
server/xupan-server
```

业务目标、首期范围和领域规则尚未最终确认。没有明确需求前，不要自行假设彩票玩法、投注、支付、结算或生产运营规则。

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
