# 超级管理后台 Element Plus 本机验收记录

> 验收日期：2026-10-21  
> 范围：仅 `/platform-admin` 前端界面重构；本机证据，不代表服务器或生产验收。

## 结论

后台共享导航、登录页与 13 个业务页面已使用 Element Plus。专项测试 14 个文件、56 项通过；类型检查、生产构建、服务健康和后台入口检查通过。浏览器对全部后台业务路由完成三种视口检查，另实际检查机器弹窗遮罩与长内容滚动。

**本机后台界面验收完成，权限现场矩阵及范围外全量测试失败单独保留。** 未在浏览器切换不同权限账号验证直接路由守卫；全量 Vitest 为 294 项中 263 通过、31 失败，失败落在非本次 `/platform-admin` 改造文件。未执行服务器或生产验收。

## 范围与保持项

- 顶部菜单、路由、字段、权限条件、API 调用、默认值、数据口径和操作顺序保持；未改 Java、Flyway、数据库、权限常量或 API 契约。
- 中心上游保留六个页签、API 数据、统计口径、凭据一次展示规则和既有操作；页面控件与后台一致。
- 8 个 `el-dialog` 设置 `close-on-click-modal="false"`；11 个 `ElMessageBox.confirm` 设置 `closeOnClickModal: false`。
- 菜单测试覆盖超级管理员、子账号、机器账号及单权限菜单。路由仍由现有守卫和 `requiredPermission` 控制，Element Plus 不承担授权判定。

## 检查结果

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 前端类型检查 | 通过 | `web` 下 `npm run typecheck`，`vue-tsc --noEmit` 零错误；修正测试中 Element Plus `ElMessageBox` mock 返回类型 |
| 后台专项测试 | 通过 | 14 个 `platformAdmin*.test.ts` 文件，56/56 通过 |
| 全量 Vitest | 有失败 | 111 个套件；294 项中 263 通过、31 失败。失败文件：`accountPanelBridge`、`adminBottomControlPanel`、`adminShell`、`authAudience`、`betBoardPanel`、`consoleScope`、`consoleWorkbench`、`loginEntryRedirect`、`machineConfigConsole`、`notFoundView`、`playerDesk`、`pointsAdmin`、`reportControlPanel`。均在本次平台后台专项范围外；不得将全量测试描述为通过 |
| 生产构建 | 通过 | Vite 构建产物已同步到 Spring Boot 静态资源目录；入口使用新 hash 资源 |
| 服务与入口 | 通过 | `http://127.0.0.1:8080/actuator/health` 返回 `UP`；`/platform-admin` 返回 HTTP 200 |
| 后台页面视口 | 通过 | 13 个业务路由在 1440×900、1280×720、768×900 检查；无后台整页横向溢出或可见加载错误 |
| 表格与弹窗 | 通过 | 表格底部滚动提示/滚动条可见；机器弹窗遮罩点击后保持打开，窄屏主体可滚动且底部操作区可见；通过取消关闭，未提交数据 |
| 入口样式隔离冒烟 | 部分通过 | `/display` 加载到移动开奖页；`/room` 被当前管理员权限守卫拒绝；`/console` 按当前会话重定向到 HBY019 演示路径；`/hby019-demo/` 根路径返回接口路径错误。静态主题 token 限定在 `.platform-admin-page`，但受权限/路径限制的入口不构成完整业务视觉回归 |
| 差异空白检查 | 通过 | `git diff --check` 无空白错误；工作区有既存 CRLF 行尾提示 |

## 剩余边界

- 浏览器没有子账号、机器账号登录态可供切换，因此真实权限角色矩阵和每个受限路由的直接访问结果未逐项现场验证；菜单权限已有自动化覆盖。
- 键盘操作做了基本交互检查，未对每个控件执行完整键盘验收。
- `/room`、`/console`、`/hby019-demo` 的浏览器结果受当前会话及路由配置影响；不能据此宣称这些页面通过视觉回归。
- 全量测试的 31 个失败需要由各自模块继续处理，不属于本次后台 UI 变更的修复范围；服务器/生产环境未验收。

## 本机服务

本机 Spring Boot 服务由 `scripts/重启本地后端后台.ps1` 启动并经健康与入口 HTTP 检查。该服务仅供本机验证，本轮没有部署或修改服务器。

## 字号可读性增量调整（2026-10-21）

- 后台 `el-config-provider` 从 `size="small"` 改为 `size="default"`；后台 Element Plus 基础字号调整为 16px、表格正文为 16px、导航为 15–16px、页面标题为 26px；中心上游提示、代码、开奖首页分区标题和登录副标题同步放大。浮层、弹窗设置对应字号变量，未更改页面字段、业务状态或 API。
- `npm run typecheck` 通过；后台 14 个专项测试文件 56/56 通过；`npm run build` 成功（1,812 个模块）。首次构建遇到声明文件写入错误，未改代码后重跑成功。
- 已运行 `mvnw resources:resources` 同步到 `target/classes` 并重启本机 8080 服务；`/actuator/health` 为 `UP`，`/platform-admin` 与 `/platform-admin/machines` 返回 HTTP 200；入口已引用新 CSS `/assets/index-C-FlFuoo.css`。
- 浏览器工具策略拒绝本轮获取页面内容，因此没有新的浏览器截图或字号后布局目视结果。请刷新本机页面确认观感；若表格或窄屏导航显得拥挤，再按具体页面微调间距/字号。
