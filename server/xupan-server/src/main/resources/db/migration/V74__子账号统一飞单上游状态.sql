-- 子账号级「统一飞单上游状态」四个字段：
--   BY220 `x_admin.feidanurl`（上游地址）/ `feidanonline`（网盘状态）/
--   `feidanmoney`（网盘余额）/ `feidanbetting`（网盘下注）。
--
-- BY220 依据（严格平移，只搬事实、不发明流程）：
--   * `application/admin/view/sub/list.html:59-61`：子账号列表有三列
--     `txtFeidanonline`（网盘状态）、`feidanmoney`（网盘余额）、`feidanbetting`（网盘下注）。
--   * `application/admin/controller/Users.php:162`：
--     `txtFeidanonline = feidanonline == 1 ? 在线 : 离线` —— 这三个是给超管看的**上游读数**。
--   * `application/admin/controller/Users.php:147-149`（`GetUserList` 读取时）：
--     若 `feidanname`（飞单账号）/`feidanpwd`（飞单密码）/`feidanid`（网盘）/`feidan`（飞单开关，1 = 开通）
--     任一缺失，就 `db('admin')->update(['feidanonline' => 0])` —— **配置不完整强制离线**，
--     并且这是"读取"的副作用（不是表单字段）。
--   * `application/admin/controller/Users.php:111-122`（被注释掉的 subLogin 分支）：
--     子账号登录上游成功后 `feidanonline = 1`；`feidanurl` 在同段注释里作为
--     `new K28(['host' => $sub['feidanurl']])` 的**上游地址**使用，
--     而 `admin/view/sub/form.html` 里**没有**这个字段（表单不设置它）。
--   * `application/console/Websocket.php:307-318`：上游返回成功 →
--     `feidanonline = 1`、`feidanmoney = 上游余额`、`feidanbetting = 上游未结算下注额`；
--     `:328-330` 重新登录失败 → `feidanonline = 0`。
--   * `application/admin/controller/Users.php:73-78`：开启飞单（`feidan == 1`）只把该子账号名下机器
--     的 `x_rbfly.flyers_online` 置 0，**不动子账号的 `feidanonline`**（本迁移不改变这条行为）。
--
-- 本地模拟边界（强制，与 V56 机器级 `agent.report_*` 同一口径）：
--   本项目**不接任何真实外部上游、不发起任何网络请求**；下面四列只是把 BY220 的上游读数
--   落到本地的等价物：
--     1. `report_url`：上游地址，**只保存不请求**，与 `agent.report_url` 同语义；
--     2. `report_online`：该子账号统一飞单会话是否在线。由"继承该子账号统一飞单的机器报网
--        登录成功 / 退出"维护（本地模拟的登录＝置机器 `agent.report_online`，不请求上游）；
--     3. `report_balance` / `report_stake`：最近一次成功登录读到的上游余额 / 未结算下注。
--        本地模拟取自那台机器的 `agent.report_balance` / `agent.report_stake`，不发起任何请求。
--   与机器端一样，这三列**不接受后台表单直接设定**（BY220 子账号表单也没有这三个字段）。
--
-- 约束命名沿用现有风格（V56 `ck_agent_report_amounts` → 这里 `ck_agent_group_report_amount`）。

ALTER TABLE agent_group ADD COLUMN report_url VARCHAR(512) NULL;
ALTER TABLE agent_group ADD COLUMN report_online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE agent_group ADD COLUMN report_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00;
ALTER TABLE agent_group ADD COLUMN report_stake DECIMAL(18,2) NOT NULL DEFAULT 0.00;

ALTER TABLE agent_group ADD CONSTRAINT ck_agent_group_report_amount
    CHECK (report_balance >= 0 AND report_stake >= 0);
