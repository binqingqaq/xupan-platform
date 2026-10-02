package com.xupan.server.platformadmin.service;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class ReportNetworkService {

    private static final String READ_PERMISSION = "REPORT_NETWORK_READ";
    private static final String WRITE_PERMISSION = "REPORT_NETWORK_WRITE";

    private final JdbcTemplate jdbc;
    private final PermissionService permissionService;

    public ReportNetworkService(JdbcTemplate jdbc, PermissionService permissionService) {
        this.jdbc = jdbc;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public List<NetworkRow> list(long operator) {
        requirePermission(operator, READ_PERMISSION);
        return jdbc.query("""
                SELECT id, network_code, display_name, website_url, status, version,
                       created_at, updated_at
                  FROM report_network
                 WHERE deleted_at IS NULL
                 ORDER BY id DESC
                """, (rs, rowNum) -> new NetworkRow(
                rs.getLong("id"), rs.getString("network_code"), rs.getString("display_name"),
                rs.getString("website_url"), rs.getString("status"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))));
    }

    @Transactional
    public NetworkRow create(NetworkInput input, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        String code = required(input.code(), "网盘键名不能为空", 64);
        String name = required(input.name(), "网盘名称不能为空", 128);
        String url = validateUrl(input.websiteUrl());
        String status = normalizeStatus(input.status());
        try {
            jdbc.update("""
                    INSERT INTO report_network
                        (network_code, display_name, website_url, status, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, code, name, url, status, operator, operator);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("REPORT_NETWORK_CODE_EXISTS", "网盘键名已存在");
        }
        Long id = jdbc.queryForObject("SELECT id FROM report_network WHERE network_code = ?", Long.class, code);
        audit(operator, "POST", "/api/admin/report-networks", id, "create:" + code);
        return require(id);
    }

    @Transactional
    public NetworkRow update(long id, NetworkInput input, long expectedVersion, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        String code = required(input.code(), "网盘键名不能为空", 64);
        String name = required(input.name(), "网盘名称不能为空", 128);
        String url = validateUrl(input.websiteUrl());
        String status = normalizeStatus(input.status());
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE report_network
                       SET network_code = ?, display_name = ?, website_url = ?, status = ?,
                           version = version + 1, updated_by = ?, updated_at = ?
                     WHERE id = ? AND deleted_at IS NULL AND version = ?
                    """, code, name, url, status, operator, Timestamp.from(Instant.now()), id, expectedVersion);
        } catch (DuplicateKeyException exception) {
            throw BusinessException.conflict("REPORT_NETWORK_CODE_EXISTS", "网盘键名已存在");
        }
        if (updated != 1) {
            throw BusinessException.conflict("REPORT_NETWORK_CONCURRENT_UPDATE", "网盘配置已被其他操作修改，请刷新后重试");
        }
        audit(operator, "PUT", "/api/admin/report-networks/" + id, id, "update:" + code);
        return require(id);
    }

    @Transactional
    public NetworkRow changeStatus(long id, String rawStatus, long expectedVersion, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        String status = normalizeStatus(rawStatus);
        int updated = jdbc.update("""
                UPDATE report_network
                   SET status = ?, version = version + 1, updated_by = ?, updated_at = ?
                 WHERE id = ? AND deleted_at IS NULL AND version = ?
                """, status, operator, Timestamp.from(Instant.now()), id, expectedVersion);
        if (updated != 1) {
            throw BusinessException.conflict("REPORT_NETWORK_CONCURRENT_UPDATE", "网盘配置已被其他操作修改，请刷新后重试");
        }
        audit(operator, "PATCH", "/api/admin/report-networks/" + id + "/status", id, "status:" + status);
        return require(id);
    }

    @Transactional
    public void delete(long id, long expectedVersion, long operator) {
        requirePermission(operator, WRITE_PERMISSION);
        int updated = jdbc.update("""
                UPDATE report_network
                   SET deleted_at = ?, version = version + 1, updated_by = ?, updated_at = ?
                 WHERE id = ? AND deleted_at IS NULL AND version = ?
                """, Timestamp.from(Instant.now()), operator, Timestamp.from(Instant.now()), id, expectedVersion);
        if (updated != 1) {
            throw BusinessException.conflict("REPORT_NETWORK_CONCURRENT_UPDATE", "网盘配置已被删除或修改，请刷新后重试");
        }
        audit(operator, "DELETE", "/api/admin/report-networks/" + id, id, "softDelete");
    }

    private NetworkRow require(long id) {
        List<NetworkRow> rows = jdbc.query("""
                SELECT id, network_code, display_name, website_url, status, version,
                       created_at, updated_at
                  FROM report_network
                 WHERE id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> new NetworkRow(
                rs.getLong("id"), rs.getString("network_code"), rs.getString("display_name"),
                rs.getString("website_url"), rs.getString("status"), rs.getLong("version"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))), id);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("REPORT_NETWORK_NOT_FOUND", "网盘配置不存在");
        }
        return rows.get(0);
    }

    private void requirePermission(long operator, String permission) {
        if (operator <= 0 || !permissionService.hasPermission(operator, permission)) {
            throw BusinessException.forbidden("REPORT_NETWORK_FORBIDDEN", "没有网盘设置权限");
        }
    }

    private void audit(long operator, String method, String path, long resourceId, String summary) {
        jdbc.update("""
                INSERT INTO sys_operation_log
                    (operator_user_id, permission_code, http_method, request_path, resource_id,
                     result, error_code, request_summary, ip_digest, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCESS', NULL, ?, NULL, CURRENT_TIMESTAMP)
                """, operator, WRITE_PERMISSION, method, path, Long.toString(resourceId), summary);
    }

    private static String validateUrl(String value) {
        String url = required(value, "网盘链接不能为空", 512);
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("REPORT_NETWORK_URL_INVALID", "网盘链接必须是有效的 http/https 地址");
        }
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw BusinessException.badRequest("REPORT_NETWORK_STATUS_INVALID", "状态无效");
        }
        return status;
    }

    private static String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw BusinessException.badRequest("REPORT_NETWORK_INVALID", message);
        }
        return value.trim();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record NetworkInput(String code, String name, String websiteUrl, String status) {
    }

    public record NetworkRow(long id, String code, String name, String websiteUrl, String status,
                             long version, Instant createdAt, Instant updatedAt) {
    }
}
