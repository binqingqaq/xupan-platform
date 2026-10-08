-- P1-C1：代理级风控上限与风控命中记录。
--
-- 背景（docs/plans/2026-10-02-上线可用收口与阶段C至D实施规划.md §5 C-1）：
--   阶段 B 的风控是**基础档**且是**全局常量**（单笔 10 万、单期敞口 50 万，写死在
--   CenterOrderService 里）。这意味着一个代理就能吃掉整期敞口，无法按代理差异化管理，
--   验收记录里风控一项也只能标"部分"。
--
-- 本迁移做三件事：
--   1. center_agent 增加**代理级**风控列：单笔上限、单期敞口上限；
--      为 NULL 表示"不限"，由服务层回退到全局默认值（保持与阶段 B 行为兼容）。
--   2. 新增风控命中原因与时间，便于在管理端直接看到"最近为什么被拒"。
--   3. 新建 center_risk_hit：**追加式**的风控命中记录，支持按规则聚合统计，
--      用于识别异常行为（同账号短时高频、反复试探、单期集中单边）。
--
-- 为什么风控命中要单独建表而不是只看 center_order.reject_code：
--   reject_code 只保留**最终**原因，且拒绝单会带 reject_code；
--   但"同一个代理连续被拒 30 次"这类异常信号需要计数与时间序列才能看出来，
--   靠逐单扫描代价高。追加式记录 + (rule, created_at) 索引让聚合变成一次范围查询。
--
-- 合规：全部为虚拟积分与内部风险敞口，不涉及任何真实资金链路。

-- 注意：每条 ADD COLUMN 拆成独立语句。H2（测试库，MODE=MySQL）不支持多列合并的 ALTER TABLE 写法。
ALTER TABLE center_agent ADD COLUMN max_single_amount DECIMAL(18,2) NULL;
ALTER TABLE center_agent ADD COLUMN max_issue_exposure DECIMAL(18,2) NULL;
ALTER TABLE center_agent ADD COLUMN last_reject_code VARCHAR(64) NULL;
ALTER TABLE center_agent ADD COLUMN last_reject_at TIMESTAMP NULL;
ALTER TABLE center_agent ADD COLUMN reject_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE center_agent ADD CONSTRAINT ck_center_agent_risk_limits CHECK (
    (max_single_amount IS NULL OR max_single_amount > 0)
    AND (max_issue_exposure IS NULL OR max_issue_exposure > 0)
    AND reject_count >= 0);

-- 风控命中记录：一次命中一行，只追加不覆盖（账本原则 3 同样适用于风控事实）。
CREATE TABLE center_risk_hit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    center_agent_id BIGINT NOT NULL,
    -- 规则码，与 CenterOrderService 的拒绝码一致
    rule_code VARCHAR(64) NOT NULL,
    -- 规则分类：LIMIT（限额类）/ EXPOSURE（敞口类）/ BEHAVIOR（行为类）/ ACCOUNT（账户类）
    rule_category VARCHAR(16) NOT NULL,
    -- 触发时的观测值与阈值，便于事后复核"当时到底超了多少"
    observed_value DECIMAL(18,2) NULL,
    threshold_value DECIMAL(18,2) NULL,
    game_code VARCHAR(32) NULL,
    issue_number VARCHAR(32) NULL,
    agent_order_no VARCHAR(80) NULL,
    detail VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 风控命中是代理的**从属事实**：代理被删除时命中记录随之删除，不应阻塞删除。
    -- （对比：中心的额度账本流水不设级联，因为账本是不允许随代理消失的凭据。）
    CONSTRAINT fk_center_risk_hit_agent FOREIGN KEY (center_agent_id) REFERENCES center_agent (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_center_risk_hit_category CHECK (rule_category IN
        ('LIMIT', 'EXPOSURE', 'BEHAVIOR', 'ACCOUNT'))
);

-- 按代理看最近命中（管理端列表）。
CREATE INDEX idx_center_risk_hit_agent ON center_risk_hit (center_agent_id, created_at, id);
-- 按规则 + 时间窗口聚合（异常行为识别的基础查询）。
CREATE INDEX idx_center_risk_hit_rule ON center_risk_hit (rule_code, created_at, id);
