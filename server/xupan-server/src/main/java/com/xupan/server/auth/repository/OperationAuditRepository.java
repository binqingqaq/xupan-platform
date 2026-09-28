package com.xupan.server.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * 操作审计写入。
 *
 * <p>审计必须独立于业务事务提交：业务回滚时审计仍要留下失败记录。但审计表的
 * {@code operator_user_id} 外键会在插入时对 {@code sys_user} 对应行加共享锁，
 * 如果业务事务此刻正持有该行的排他锁（例如管理员重置自己的密码、上传自己的头像、
 * 修改自己的角色），独立事务就会等待自己，最终以 50 秒锁等待超时失败并回滚业务。
 *
 * <p>因此：业务事务存在时，审计写入登记到事务完成回调，等业务事务提交或回滚、锁释放后
 * 再用独立事务写入；没有业务事务时直接写入。
 */
@Repository
public class OperationAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate auditTransaction;

    public OperationAuditRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                       String resourceId, String result, String errorCode, String requestSummary,
                       String ip, Instant createdAt) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    write(operatorUserId, permissionCode, httpMethod, requestPath, resourceId, result,
                            errorCode, requestSummary, ip, createdAt);
                }
            });
            return;
        }
        write(operatorUserId, permissionCode, httpMethod, requestPath, resourceId, result,
                errorCode, requestSummary, ip, createdAt);
    }

    private void write(Long operatorUserId, String permissionCode, String httpMethod, String requestPath,
                       String resourceId, String result, String errorCode, String requestSummary,
                       String ip, Instant createdAt) {
        String safeSummary = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(requestSummary, 2000), "requestSummary");
        String safeResourceId = LoginAuditRepository.rejectSensitive(
                LoginAuditRepository.truncate(resourceId, 128), "resourceId");
        auditTransaction.executeWithoutResult(status -> jdbcTemplate.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operatorUserId, LoginAuditRepository.truncate(permissionCode, 128),
                LoginAuditRepository.truncate(Objects.requireNonNull(httpMethod, "httpMethod"), 16),
                LoginAuditRepository.truncate(Objects.requireNonNull(requestPath, "requestPath"), 255),
                safeResourceId, requiredResult(result), LoginAuditRepository.truncate(errorCode, 64),
                safeSummary, LoginAuditRepository.digest(ip), timestamp(createdAt)));
    }

    private static String requiredResult(String result) {
        if (!"SUCCESS".equals(result) && !"FAILURE".equals(result)) {
            throw new IllegalArgumentException("非法操作审计结果");
        }
        return result;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? new Timestamp(System.currentTimeMillis()) : Timestamp.from(value);
    }
}
