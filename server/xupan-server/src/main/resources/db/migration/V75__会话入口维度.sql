-- 会话入口维度（实施计划 2026-10-08 阶段 5，使用者 2026-10-08 答复 Q2 = C1「一起做入口级独立会话」）。
--
-- 背景：/console（BY220 robot.php，机器账号）与 /platform-admin（BY220 admin.php，超管/子账号）
-- 此前共用同一个刷新 Cookie xupan_refresh，且 auth_session 里**没有"入口"这个维度**，
-- 于是同一个浏览器只能保住一份后台登录态：敲哪个入口都用同一份会话，在一边退出还会把另一边踢下线。
--
-- 做法：为会话增加 entry 列，让平台入口与代理入口的会话在库里可区分。
-- 刷新时由 TokenService 校验"请求入口 == 会话 entry"，不一致一律 401 并只清该入口的 Cookie，
-- 绝不静默把某个入口的会话切换成另一个入口的身份。
--
-- 为什么 DEFAULT 'PLATFORM' 是刻意的：
--   升级前已签发的会话（刷新令牌 30 天）没有入口信息，回填会把在用的机器账号会话
--   误标成平台入口；因此统一按"老会话只对平台入口有效"处理，
--   代理入口（/console）需要重登一次。这与计划 §9「老会话兼容」一致。
--
-- 说明：CHECK 只允许 PLATFORM / CONSOLE 两个后台入口。
--   玩家链接会话（PLAYER_LINK）不属于这两个后台入口，仍按默认值 PLATFORM 落库，
--   其刷新鉴权继续由 auth_mode = 'PLAYER_LINK' 负责（见 TokenService.issuePlayerLink）。

ALTER TABLE auth_session
    ADD COLUMN entry VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';

ALTER TABLE auth_session
    ADD CONSTRAINT ck_auth_session_entry
        CHECK (entry IN ('PLATFORM', 'CONSOLE'));
