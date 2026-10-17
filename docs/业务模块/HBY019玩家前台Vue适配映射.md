# HBY019 玩家前台 Vue 适配映射

## 范围

`/hby019-demo/player.html` 与 `/hby019-demo/player-ui1.html` 由 Vue Router 页面组件托管独立 iframe，分别加载 `web/public/hby019-demo/source/player.html`、`player-ui1.html`。iframe 内直接运行源文档 DOM、内联 CSS、本地 vendor、本地 bridge、对应官方 runtime 和页面 inline handlers；Vue 不复制或重绘玩家页 DOM，也不改写 Xupan 正式玩家页 `/room`。隔离 iframe 让 jQuery、Alpine、Layui、全局 CSS 和事件监听不进入 Xupan 主 document。

| 源 DOM / 资源 | Vue 适配方式 | 交互与数据边界 |
| --- | --- | --- |
| UI4 `player.html` 的完整文档、内联 CSS、Bulma、Layui | iframe 直接加载本地源 HTML | 保留源页面 DOM/CSS 原始层级 |
| UI1 `player-ui1.html` 的完整文档、内联 CSS、Bulma、Layui | iframe 独立加载 UI1 HTML，不复用 UI4 布局 | 由 UI1 runtime 绑定对应事件 |
| `cdn.min.js` Alpine 初始化 | 由 UI4 HTML 原生脚本顺序加载 | 保留 `x-data`、`x-show`、`x-for` 等源表达式 |
| `official-player-runtime.js` / `official-player-runtime-ui1.js` | 由各自源 HTML 原生加载 | 保留源快捷区、流水、聊天键盘、历史、弹窗和格式切换处理器 |
| `/chat/index/timer`、`msgrecord`、`autoinfo`、`gethis`、`getrecord`、`gethistory`、`setnum` | 交给 `player-local-bridge.js` | 只返回本地 fixture；快捷金额保存到浏览器 localStorage |
| 页面 WebSocket `/ws` | bridge 提供内存 LocalSocket | 发送内容只生成演示消息，不创建订单、不改余额、不持久化业务事实 |
| UI1/UI4 切换目的地址 | 源页面内的本地源地址在 iframe 内切换 | iframe 继续加载同源 `/hby019-demo/source/` 文件；无真实上游访问链接 |
| fetch、WebSocket、jQuery AJAX、sendBeacon | bridge 禁止外部数据请求；仅覆盖声明的本地 fixture URL | 本演示不调用 Xupan `/api` 或真实 WS；源页未使用的原生 XHR 不是业务数据通路 |

## DOM 与事件覆盖

| 页面区域 | 源选择器 / 结构 | 源事件及适配器数据 |
| --- | --- | --- |
| 聊天内容 | `.msgcontent`、`.msgleft`、`.msgright` | 官方 runtime 渲染本地聊天 fixture；发送经 LocalSocket 返回演示回复 |
| 快捷投注 | `.kuaijie`、`.changeGame`、`.grid-container`、`.bet-button`、`.kuaikey`、`.kuaisu`、`.shezhi` | 原始 click 处理器保留；提交只显示本地演示结果，金额设置本地保存 |
| 底部输入与键盘 | `.divBottom .send`、`#txtMsg`、`.vkb`、`.inpvkb li` | 源键盘点击、发送、展开与输入处理器保留；查询/下注/上下分命令均由 bridge 本地回应 |
| 历史开奖 | `#history`、`.zoushi`、`.guize`、`#changeQiu` | 保留原始面板/事件；历史由 fixture 提供。源资料中的走势图精灵图缺失 |
| 个人流水 | `.uInfo`、`.btnClass`、`#myDiv`、`.record-row` | 官方 runtime 用 `getrecord` fixture 渲染并处理未结/已结、行展开 |
| 字体与声音设置 | `.fs-size`、`.fs-bold`、`.uInfo` | 使用官方 runtime 的字号、粗体状态和本地偏好处理 |
| 玩法说明与未飞出 | `#playNoticeOverlay`、`#flyFailOverlay` | 保留源页按钮和 inline handler；窗口内容来自源 HTML 与本地 fixture |

源 runtime 中其余的快捷投注选择事件也保留：`.button-row .btn`、`.grid-container .grid-item`、`.button-money .btn`、`.je`、`.jian`、`.jia`、`.chouma`、`.item-container`、`.chongzhi`、`.key .numberStyle`。两个原始页面模板都随 body 一起挂载；未使用 UserRoom.vue 的自绘键盘或账单组件。

## Runtime 请求与本地行为

| 源 runtime 调用 | 本地处理 | 结果边界 |
| --- | --- | --- |
| `POST /chat/index/timer` | local bridge 固定期号/开奖快照 | runtime 原有倒计时继续按本地时间递减；不是实时开奖 |
| `POST /chat/index/msgrecord`、`autoinfo`、`gethis` | local bridge 合成聊天、统计与历史消息 | 仅填充官方 runtime 的消息 DOM |
| `POST /chat/index/getrecord`、`gethistory` | local bridge 提供固定流水/历史开奖 fixture | 继续调用官方 runtime 的行、标签及历史列表渲染 |
| `POST /chat/index/setnum` | 校验五个快捷金额并保存在 localStorage | 仅影响本浏览器演示偏好 |
| `new WebSocket(wss://<当前主机>/ws...)` | local bridge 要求同源 `/ws` 后替换为内存 LocalSocket | 发送仅回显本地模拟回复；无服务端投递 |
| runtime 其他 `$.ajax` URL | bridge 统一拒绝并显示“本地演示”提示 | 不转发到真实服务 |
| `fetch`、原生 XHR、`sendBeacon` | Vue 适配器阻断 | 不产生 Xupan API 或参考站请求 |
| 外部链接 | bridge 在 iframe 文档捕获阶段阻止 | 留在本地演示页 |

未提供真实账号状态/权限、连续实时开奖、聊天持久化、投注确认与结算、上下分审批、取消订单、走势弹窗数据、外部语音/充值/上游服务。源页面里没有可见入口的运行时处理器仍原样加载，但不能据此视为有实际业务效果。

## 静态缺失资源与限制

- `static/play/ball_hk6.png` 是源走势弹窗引用的球图精灵；本地捕获清单记录普通 GET 返回 `404 text/html`，本实现不绘制或伪造替代图。
- `webfont.svg` 是图标 CSS 的末级回退；本机已提供更前置的 TTF 字体文件，故该回退缺失不等于图标字体一定不可用。
- 页面上的聊天、期号、开奖、记录和快捷金额均为本地演示数据，不代表 Xupan 数据或实时状态。
- iframe 内的所有静态 HTML/CSS/JS/字体/头像从本项目 `/hby019-demo/source/` 提供；Vue 主 document 不执行源脚本，也不受源页面全局样式影响。
