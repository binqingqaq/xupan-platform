-- 子账号「统一飞单」的报网类型（BY220 机器的 x_robot.flyers_type 来源）。
--
-- BY220 依据（严格平移，只搬事实、不发明流程）：
--   * `application/rbt/controller/Index.php:745`：机器账号在 `/console` 选定网盘后
--     `db('robot')->where(['UserName' => USER_ID])->update(['flyers_type' => $flyinfo['code']])`
--     —— 机器的 `flyers_type` 直接取**所选网盘记录的 `code`**。
--   * `application/console/Websocket.php:160-165`：`$robot['flyers_type']` 为 `K28` / `ls8899` / `wns`
--     时分别走不同的上游驱动；为空则直接不飞单。
--   * `application/admin/controller/Index.php:42-98`：BY220 网盘表 `x_rbwp` 的字段是
--     `name / code / websiteUrl / searchCode / apiKey / apiVersion / status` —— **没有类型列**，
--     所以 BY220 里"网盘编码"与"报网类型"本来就是同一个值，靠 `x_rbwp.code` 兼任。
--
-- 本项目把这两件事拆成两列（这是安全/工程约束造成的差异，需要单独记录）：
--   1. `agent_group.report_network_code`（V35）：自有的"网盘编码"事实，对应 BY220 `x_rbwp.code`；
--   2. 本迁移新增的 `agent_group.report_type`：BY220 里由网盘 `code` 兼任、本项目需要显式保存的
--      "报网类型"（等价于 `x_robot.flyers_type`，见 `agent.report_type`，V56）。
--   子账号开启统一飞单（`unified_report_enabled`）时，其名下机器在自身没有飞单配置的情况下
--   读取到的报网类型取这里的值；子账号未设置时回退机器自身的 `agent.report_type`。
--
-- 取值与 V56 的 `ck_agent_report_type`（`agent.report_type`）完全一致，便于两列互相回退；
-- NULL 表示"子账号未设置报网类型"（不是"无报网"，"无"是显式取值 `NONE`）。

ALTER TABLE agent_group ADD COLUMN report_type VARCHAR(32) NULL;

ALTER TABLE agent_group ADD CONSTRAINT ck_agent_group_report_type
    CHECK (report_type IS NULL OR report_type IN ('NONE', 'TT', 'K28', 'LS8899', 'WNS', 'LX', 'HY', 'CENTER'));
