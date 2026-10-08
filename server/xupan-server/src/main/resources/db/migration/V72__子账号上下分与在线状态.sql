-- 子账号在线状态与最近登录时间（BY220 `x_admin.online` / `x_admin.logtime`）。
--
-- BY220 依据（严格平移）：
--   * `application/admin/controller/Login.php::check()`（第 51-56 行）登录成功后
--     `db('admin')->where('UserName',$username)->update(['online' => 1, 'logtime' => time(), ...])`；
--     `Login.php::logout()`（第 67 行）退出时 `update(['online' => 0])`。
--   * `Login.php::check()` 只校验密码（`password_verify`）与 `time` 失效时间（第 30-37 行），
--     **不校验 `online`**：所以 `online` 只是"当前是否在线"的状态展示，
--     不是启用/禁用开关，不能用它拦截登录。
--   * `application/admin/view/sub/form.html`（第 104-111 行）在 `online==1` 时显示「在线」开关，
--     超管可以手工把子账号切换为在线/下线；`Users.php::add()` 第 55 行即 `$data['online'] = isset($data['online']) ? 1 : 0`。
--
-- 合规：这里只记录在线状态与登录时间，不涉及任何真实资金语义。

ALTER TABLE agent_group ADD COLUMN online TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE agent_group ADD COLUMN logtime TIMESTAMP NULL;
