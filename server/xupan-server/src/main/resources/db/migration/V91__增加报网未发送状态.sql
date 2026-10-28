ALTER TABLE report_upstream_order DROP CONSTRAINT ck_report_upstream_order_status;
ALTER TABLE report_upstream_order
    ADD CONSTRAINT ck_report_upstream_order_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMEOUT', 'CANCELLED', 'REFUNDED', 'SETTLED', 'MANUAL', 'NOT_SENT'));
