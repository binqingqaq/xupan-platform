-- 子账号积分与机器积分的资金联动（BY220 语义）。
--
-- BY220 里子账号（`x_admin.score`）就是名下机器的**上游资金池**：
--   * `application/admin/controller/Rb.php` 子账号建机器时 `setDec('score', 机器初始积分)`；
--   * `application/common.php::rbUpdown` 给机器上分时 `admin.setDec` + `robot.setInc`，下分时反向；
--   * 超管直接建的机器没有子账号（`uid` 为空），不涉及资金池。
-- 因此账本里需要两种新的流水类型来区分「划拨」与「超管直接调整」：
--   * MACHINE_ALLOCATE 子账号 → 机器（子账号 OUT / 机器 IN）
--   * MACHINE_RETURN   机器 → 子账号（机器 OUT / 子账号 IN）
--
-- 合规：仅虚拟积分内部流转，不涉及任何真实资金。

ALTER TABLE agent_ledger DROP CONSTRAINT ck_agent_ledger_entry_type;
ALTER TABLE agent_ledger ADD CONSTRAINT ck_agent_ledger_entry_type CHECK (entry_type IN (
    'MACHINE_INIT', 'MACHINE_ADJUST', 'SUB_ACCOUNT_INIT', 'SUB_ACCOUNT_ADJUST',
    'PLAYER_TOP_UP', 'PLAYER_DOWN', 'OPENING_BALANCE',
    'MACHINE_ALLOCATE', 'MACHINE_RETURN'));
