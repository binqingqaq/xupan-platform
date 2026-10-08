-- 后台入口权限收敛：从 ADMIN / SUB_ACCOUNT 撤销 AGENT_CONSOLE_READ
-- （实施计划 2026-10-08 阶段 4，使用者 2026-10-08 答复第 2 题选 B：
--  "敲 /console 直接以管理员身份进入" → 严格对齐 BY220，/console 只接受机器账号）。
--
-- BY220 依据（第一参考，禁止自行发明）：
--   1) application/rbt/controller/Login.php:55-77 —— 代理后台（robot.php）登录**只查 x_robot 表账号**；
--   2) application/rbt/common/Base.php:9-20     —— 代理后台 Base 只解析机器身份，整条链路都在机器域内。
--   x_admin（超级管理员 / 子账号）根本不在这套域里，因此"ADMIN / SUB_ACCOUNT 能进 /console"
--   是既有偏离（V34 把 AGENT_CONSOLE_READ 授给了 ADMIN，V56 又补给了 SUB_ACCOUNT）。
--
-- 本迁移只做**权限收敛**，不做别的：
--   * AGENT_CONSOLE_READ 权限码本身保留（sys_permission 行不动，PermissionCode 枚举也不动）；
--   * /api/agent/** 仍然只认 AGENT_CONSOLE_READ（撤销后只有机器账号能调，符合预期）；
--   * AGENT_PLAYER_MANAGE **必须保留**给 ADMIN / SUB_ACCOUNT：平台后台的玩家工作台
--     （/platform-admin/player-workbench）与 /api/admin/player-desk/** 靠它放行
--     （SecurityConfig 的 /api/admin/player-desk/** 会在本次改动里补上 PERM_AGENT_PLAYER_MANAGE）。
--
-- 幂等：重复执行删除 0 行。前向修复（如需回滚）= 新增迁移重新 INSERT 授权行，不改本文件。

DELETE FROM sys_role_permission
 WHERE permission_id IN (
           SELECT id FROM sys_permission WHERE permission_code = 'AGENT_CONSOLE_READ'
       )
   AND role_id IN (
           SELECT id FROM sys_role WHERE role_code IN ('ADMIN', 'SUB_ACCOUNT')
       );
