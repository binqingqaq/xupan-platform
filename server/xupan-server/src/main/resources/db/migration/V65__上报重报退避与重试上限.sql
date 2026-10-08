-- P0-2：上报重报的分级退避与重试上限。
--
-- 缺陷背景（docs/plans/2026-10-02-上线可用收口与阶段C至D实施规划.md §4 P0-2）：
--   ReportUpstreamOrderRepository.findSubmittableBets 用 `ORDER BY b.id LIMIT ?` 取待上报注单，
--   且重报状态集合包含 REJECTED / TIMEOUT。注单按 id 升序取，最老的 50 笔一旦长期停留在失败/超时，
--   批次会被它们永久占满，**此后所有新注单再也排不上上报**，且没有任何告警。
--   V58 建表时没有 next_retry_at，也没有重试计数，因此既无法退避也无法封顶。
--
-- 修法：
--   1. 新增 next_retry_at：只有到达该时间的注单才参与扫描（默认立即，兼容存量数据）；
--   2. 新增 attempt_count：记录已尝试次数，超过上限转 MANUAL（人工待处置），不再占用批次；
--   3. 扫描索引改为 (status, next_retry_at, id)，让"到点才捞"成为索引范围扫描而不是全表排序。
--
-- 状态机扩展：
--   V58 的 CHECK 约束原本只允许 PENDING/ACCEPTED/REJECTED/TIMEOUT/CANCELLED/REFUNDED/SETTLED。
--   本迁移新增 MANUAL（人工待处置）：重试超过上限后落此状态，界面可查、可人工重报，
--   但不再参与自动轮询——这是消除"头部阻塞"的关键，否则失败单永远在候选集合里。
--
-- 与 BY220 的关系（需用户确认的偏离）：
--   BY220 的 flyers_status = 3 轮询没有重试计数、没有退避，靠 Swoole 定时器自然节流。
--   本迁移新增的"退避 + 次数上限 + 人工待处置"属于**有意偏离**，目的是消除头部阻塞；
--   已在规划文档 §11 待确认项 2 登记，实现上通过配置项可关闭退避（backoff 全为 0 即等同原行为）。
--
-- 合规：只做内部持久化，不发起任何外部网络请求。

-- 注意：这里刻意把每条 ADD COLUMN 拆成独立语句。
-- H2（测试库，MODE=MySQL）不支持 `ALTER TABLE t ADD COLUMN a, ADD COLUMN b` 这种多列合并写法，
-- 而 MySQL 两种都支持；拆开写可以同时满足两边。
ALTER TABLE report_upstream_order ADD COLUMN next_retry_at TIMESTAMP NULL;
ALTER TABLE report_upstream_order ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

-- 存量行：已有尝试的行按当前状态给一次立即重试机会（next_retry_at = 创建时间，即"已到期"）。
UPDATE report_upstream_order
   SET attempt_count = attempt_no,
       next_retry_at = created_at
 WHERE next_retry_at IS NULL;

ALTER TABLE report_upstream_order ADD CONSTRAINT ck_report_upstream_order_attempt_count CHECK (attempt_count >= 0);

-- 扩展状态机约束以容纳 MANUAL。
ALTER TABLE report_upstream_order DROP CONSTRAINT ck_report_upstream_order_status;
ALTER TABLE report_upstream_order ADD CONSTRAINT ck_report_upstream_order_status CHECK (status IN
    ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'CANCELLED', 'REFUNDED', 'SETTLED', 'MANUAL'));

-- 扫描用索引：按状态 + 到点时间 + id 排序取批次。
CREATE INDEX idx_report_upstream_order_retry
    ON report_upstream_order (status, next_retry_at, id);

-- 原索引 (status, created_at, id) 仍被管理端按状态统计使用，保留不动。
