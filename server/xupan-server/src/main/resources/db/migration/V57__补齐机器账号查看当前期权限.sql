-- 修复机器账号在代理后台（/console，BY220 robot.php）缺少"查看当前期"权限的缺陷。
--
-- 背景：用户 2026-10-02 实测发现机器账号登录 /console 后顶部提示
-- "当前账号没有执行此操作的权限"，且期号、开奖、倒计时为空。
-- 根因：/console 顶部状态栏与下注榜依赖 /api/demo/game/current 与 /api/demo/game/catalog，
-- 两者都要求 PERM_GAME_CURRENT_READ；该权限在 V34/V54/V56 里只授给了 ADMIN 与 SUB_ACCOUNT，
-- 机器账号（AGENT 角色）没有，于是被 URL 层 403。
-- BY220 的 robot.php 代理后台本身就能看到当前期号、开奖与倒计时，属于平移缺失，因此补齐。
--
-- 只补"查看当前期/彩种目录"这一项只读权限；机器账号仍然没有
-- GAME_ODDS_WRITE、GAME_BET_PLACE、USER_MANAGE、MACHINE_MANAGE 等管理员能力。
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.permission_code = 'GAME_CURRENT_READ'
WHERE r.role_code = 'AGENT'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission existing
      WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
