# 移动开奖页完整内容备份（2026-11-07）

备份的是精简前首页、按需片段和页面脚本。图片、CSS 等未修改资源仍由 `web/public/display/mobile-replica/official/123论坛_files/` 共用，不重复复制。

恢复内容时，从 `index.html` 中选择需要的模块复制回 `web/public/display/mobile-replica/index.html`；资源文件仍保留在上述目录。需要发布恢复结果时，重新运行 `npm run build`、提交并部署。

本次从活动首页移除 `widgetBox40585`、`#allsite` 和 `#guilvsite`，仅保留底部可见导航栏。
