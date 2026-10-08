-- 玩家删除口径严格对齐 BY220：**物理删除玩家行 + 余额退回机器**，注单与流水历史保留。
--
-- BY220 依据（逐行核过）：application/rbt/controller/Index.php:235-243
--   db('robot')->where('UserName', $user['uid'])->setInc('score', $user['score']);  // ① 余额退回机器
--   db('rbuser')->where('wxid', ...)->where('uid', USER_ID)->delete();              // ② 物理删除玩家行
--   setCount(USER_ID);                                                              // ③ 重算统计
-- BY220 能这样删是因为 x_record / x_money_log 里**冗余存了玩家与机器**
-- （x_record.name = 玩家 wxid、x_record.NickName、x_record.uid = 机器 UserName），
-- 且没有外键。xupan 有外键，因此必须：
--   1) 把指向玩家行的外键改为「可空 + ON DELETE SET NULL」，让历史行留下来；
--   2) **补玩家与机器的可读快照**，否则 SET NULL 之后界面/报表会变空
--      （计划 §6 点名的"最容易漏的地方"）；
--   3) 机器归属快照让机器范围报表在玩家行已删时仍能把这些历史统计进去
--      （默认 JOIN demo_user_account 取 agent_id 的做法会静默把历史漏掉，与 BY220 相反）。
--
-- 合规：只涉及虚拟积分，不涉及任何真实资金。
-- 范围：本迁移只服务「工作包 DEL（玩家物理删除）」；不得被其它 lane 复用。

-- ---------------------------------------------------------------------------
-- 1) 历史留存快照列
--    player_code_snapshot = demo_user_account.member_code（会员编号，界面上展示的「会员 ID」）
--    player_name_snapshot = 删除时的玩家昵称
--    agent_id_snapshot    = 删除时的机器归属（BY220 x_record.uid 的对应物）
--    player_kind_snapshot = 删除时的玩家分类（普/托，BY220 x_record.isTuo 的对应物）
--    快照列**故意不建外键**：它们是历史事实，不是活动引用。
-- ---------------------------------------------------------------------------
ALTER TABLE game_bet ADD COLUMN player_code_snapshot VARCHAR(64) NULL;
ALTER TABLE game_bet ADD COLUMN player_name_snapshot VARCHAR(128) NULL;
ALTER TABLE game_bet ADD COLUMN agent_id_snapshot BIGINT NULL;
ALTER TABLE game_bet ADD COLUMN player_kind_snapshot VARCHAR(16) NULL;

ALTER TABLE demo_balance_ledger ADD COLUMN player_code_snapshot VARCHAR(64) NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN player_name_snapshot VARCHAR(128) NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN agent_id_snapshot BIGINT NULL;
ALTER TABLE demo_balance_ledger ADD COLUMN player_kind_snapshot VARCHAR(16) NULL;

ALTER TABLE game_bet_edit_record ADD COLUMN player_code_snapshot VARCHAR(64) NULL;
ALTER TABLE game_bet_edit_record ADD COLUMN player_name_snapshot VARCHAR(128) NULL;
ALTER TABLE game_bet_edit_record ADD COLUMN agent_id_snapshot BIGINT NULL;

ALTER TABLE player_point_request ADD COLUMN player_code_snapshot VARCHAR(64) NULL;
ALTER TABLE player_point_request ADD COLUMN player_name_snapshot VARCHAR(128) NULL;

-- 换名记录的「主体」就是昵称（old/new 两列已可读），只补会员编号以便识别是哪位玩家。
ALTER TABLE player_name_change_record ADD COLUMN player_code_snapshot VARCHAR(64) NULL;

-- 存量行回填：尚未删除的玩家仍能 JOIN 到 demo_user_account，把机器归属与玩家分类补进快照。
-- 用相关子查询（MySQL 与 H2 都可执行），避免 MySQL 专有的多表 UPDATE 语法。
UPDATE game_bet
   SET agent_id_snapshot = (SELECT a.agent_id FROM demo_user_account a WHERE a.id = game_bet.user_id)
 WHERE user_id IS NOT NULL AND agent_id_snapshot IS NULL;

UPDATE game_bet
   SET player_kind_snapshot = (SELECT a.player_kind FROM demo_user_account a WHERE a.id = game_bet.user_id)
 WHERE user_id IS NOT NULL AND player_kind_snapshot IS NULL;

UPDATE demo_balance_ledger
   SET agent_id_snapshot = (SELECT a.agent_id FROM demo_user_account a WHERE a.id = demo_balance_ledger.user_id)
 WHERE user_id IS NOT NULL AND agent_id_snapshot IS NULL;

UPDATE demo_balance_ledger
   SET player_kind_snapshot = (SELECT a.player_kind FROM demo_user_account a WHERE a.id = demo_balance_ledger.user_id)
 WHERE user_id IS NOT NULL AND player_kind_snapshot IS NULL;

UPDATE game_bet_edit_record
   SET agent_id_snapshot = (SELECT a.agent_id FROM demo_user_account a WHERE a.id = game_bet_edit_record.account_id)
 WHERE account_id IS NOT NULL AND agent_id_snapshot IS NULL;

CREATE INDEX idx_game_bet_agent_snapshot ON game_bet (agent_id_snapshot, id);
CREATE INDEX idx_demo_balance_ledger_agent_snapshot ON demo_balance_ledger (agent_id_snapshot, id);
CREATE INDEX idx_game_bet_edit_agent_snapshot ON game_bet_edit_record (agent_id_snapshot, id);

-- ---------------------------------------------------------------------------
-- 2) 保留历史：三张历史表指向玩家账号的外键改为「可空 + ON DELETE SET NULL」
--    game_bet.user_id 原本 NOT NULL 且带 DEFAULT 1（V2 的历史遗留），
--    改为可空后默认值也一并去掉，避免新注单落回不存在的 1 号账号。
-- ---------------------------------------------------------------------------
ALTER TABLE game_bet DROP CONSTRAINT fk_game_bet_demo_user;
ALTER TABLE game_bet MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE game_bet ADD CONSTRAINT fk_game_bet_demo_user
    FOREIGN KEY (user_id) REFERENCES demo_user_account (id) ON DELETE SET NULL;

ALTER TABLE demo_balance_ledger DROP CONSTRAINT fk_demo_balance_ledger_user;
ALTER TABLE demo_balance_ledger MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE demo_balance_ledger ADD CONSTRAINT fk_demo_balance_ledger_user
    FOREIGN KEY (user_id) REFERENCES demo_user_account (id) ON DELETE SET NULL;

ALTER TABLE game_bet_edit_record DROP CONSTRAINT fk_game_bet_edit_account;
ALTER TABLE game_bet_edit_record MODIFY COLUMN account_id BIGINT NULL;
ALTER TABLE game_bet_edit_record ADD CONSTRAINT fk_game_bet_edit_account
    FOREIGN KEY (account_id) REFERENCES demo_user_account (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 3) 身份域的业务历史：与注单同口径（可空 + SET NULL），不随玩家身份物理删除
--    player_point_request / player_name_change_record 指向 sys_user。
-- ---------------------------------------------------------------------------
ALTER TABLE player_point_request DROP CONSTRAINT fk_point_request_user;
ALTER TABLE player_point_request MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE player_point_request ADD CONSTRAINT fk_point_request_user
    FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE SET NULL;

ALTER TABLE player_name_change_record DROP CONSTRAINT fk_player_name_change_user;
ALTER TABLE player_name_change_record MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE player_name_change_record ADD CONSTRAINT fk_player_name_change_user
    FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 4) 机器账本新增流水类型：玩家删除退回余额
--    BY220 不记这条流水（直接 setInc('score')），我们为了审计必须记 —— **这是超集**，
--    已在实施报告与缺口表中如实登记。
--    已确认：Java/前端没有任何 `entry_type IN (...)` 白名单过滤，只有本 CHECK 需要放开。
-- ---------------------------------------------------------------------------
ALTER TABLE agent_ledger DROP CONSTRAINT ck_agent_ledger_entry_type;
ALTER TABLE agent_ledger ADD CONSTRAINT ck_agent_ledger_entry_type CHECK (entry_type IN (
    'MACHINE_INIT', 'MACHINE_ADJUST', 'SUB_ACCOUNT_INIT', 'SUB_ACCOUNT_ADJUST',
    'PLAYER_TOP_UP', 'PLAYER_DOWN', 'OPENING_BALANCE',
    'MACHINE_ALLOCATE', 'MACHINE_RETURN', 'PLAYER_DELETE_REFUND'));
