package com.xupan.server.displayobserver;

import com.xupan.server.auth.service.PermissionService;
import com.xupan.server.web.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DisplayObserverBindingService {

    private static final String READ_PERMISSION = "PLATFORM_SETTINGS_READ";
    private static final String WRITE_PERMISSION = "PLATFORM_SETTINGS_WRITE";
    private static final Pattern PUBLIC_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16}");
    private static final Pattern SENSITIVE_REASON_PATTERN =
            Pattern.compile("(?i)(https?://|/33/|/display/observe/|token=|password|密码|口令)");

    private final DisplayObserverBindingRepository repository;
    private final PermissionService permissionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public DisplayObserverBindingService(DisplayObserverBindingRepository repository,
                                         PermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public BindingView getBinding(long operatorUserId) {
        requirePlatformPermission(operatorUserId, READ_PERMISSION);
        return bindingView();
    }

    @Transactional(readOnly = true)
    public List<DisplayObserverBindingRepository.MachineCandidate> eligibleMachines(long operatorUserId) {
        requirePlatformPermission(operatorUserId, READ_PERMISSION);
        return repository.findEligibleMachines();
    }

    @Transactional(readOnly = true)
    public List<DisplayObserverBindingRepository.ViewerCandidate> eligibleBots(long agentId, long operatorUserId) {
        requirePlatformPermission(operatorUserId, READ_PERMISSION);
        if (!repository.isEligibleMachine(agentId)) {
            throw BusinessException.badRequest("DISPLAY_OBSERVER_MACHINE_INVALID", "请选择有效的展示机器");
        }
        return repository.findEligibleBots(agentId);
    }

    @Transactional
    public BindingView bind(long agentId, long viewerUserId, long expectedVersion, long operatorUserId) {
        requirePlatformPermission(operatorUserId, WRITE_PERMISSION);
        if (expectedVersion != 0) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BINDING_VERSION_CONFLICT", "设置已变化，请刷新后重试");
        }
        DisplayObserverBindingRepository.TargetSnapshot target = repository
                .lockEligibleTarget(agentId, viewerUserId)
                .orElseThrow(() -> BusinessException.badRequest("DISPLAY_OBSERVER_TARGET_INVALID",
                        "展示托必须是所选有效机器名下、已停用自动行为的有效托"));
        String publicId = newPublicId();
        try {
            repository.insert(target, publicId, operatorUserId);
        } catch (DuplicateKeyException conflict) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_ALREADY_BOUND", "开奖页只读入口已经绑定，请刷新设置");
        }
        repository.audit("BIND", target.agentId(), target.viewerUserId(), operatorUserId, 1,
                "首次绑定开奖页只读观察入口");
        return bindingView();
    }

    @Transactional
    public BindingView setEnabled(boolean enabled, long expectedVersion, String reason, long operatorUserId) {
        requirePlatformPermission(operatorUserId, WRITE_PERMISSION);
        String normalizedReason = requiredReason(reason);
        DisplayObserverBindingRepository.BindingRow current = lockBinding(expectedVersion);
        if (current.enabled() == enabled) return bindingView();
        if (enabled && !validTarget(current)) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_TARGET_INVALID",
                    "展示机器或展示托状态无效，入口不能启用");
        }
        long nextVersion = current.version() + 1;
        if (repository.update(current.version(), enabled, newPublicId(), operatorUserId) != 1) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BINDING_VERSION_CONFLICT", "设置已变化，请刷新后重试");
        }
        repository.audit(enabled ? "ENABLE" : "DISABLE", current.agentIdSnapshot(), current.viewerUserIdSnapshot(),
                operatorUserId, nextVersion, normalizedReason);
        return bindingView();
    }

    @Transactional
    public BindingView rotate(String reason, long expectedVersion, long operatorUserId) {
        requirePlatformPermission(operatorUserId, WRITE_PERMISSION);
        String normalizedReason = requiredReason(reason);
        DisplayObserverBindingRepository.BindingRow current = lockBinding(expectedVersion);
        if (current.enabled() && !validTarget(current)) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_TARGET_INVALID", "展示机器或展示托状态无效，不能换链");
        }
        long nextVersion = current.version() + 1;
        if (repository.update(current.version(), current.enabled(), newPublicId(), operatorUserId) != 1) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BINDING_VERSION_CONFLICT", "设置已变化，请刷新后重试");
        }
        repository.audit("ROTATE", current.agentIdSnapshot(), current.viewerUserIdSnapshot(), operatorUserId,
                nextVersion, normalizedReason);
        return bindingView();
    }

    @Transactional
    public BindingView resetBinding(long agentId, long viewerUserId, long expectedVersion,
                                    String reason, long operatorUserId) {
        requirePlatformPermission(operatorUserId, WRITE_PERMISSION);
        String normalizedReason = requiredReason(reason);
        DisplayObserverBindingRepository.BindingRow current = lockBinding(expectedVersion);
        if (current.enabled()) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_RESET_REQUIRES_DISABLED",
                    "请先停用只读入口，再执行绑定重置");
        }
        DisplayObserverBindingRepository.TargetSnapshot target = repository
                .lockEligibleTarget(agentId, viewerUserId)
                .orElseThrow(() -> BusinessException.badRequest("DISPLAY_OBSERVER_TARGET_INVALID",
                        "展示托必须是所选有效机器名下、已停用自动行为的有效托"));
        long nextVersion = current.version() + 1;
        if (repository.rebind(current.version(), target, newPublicId(), operatorUserId) != 1) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BINDING_VERSION_CONFLICT", "设置已变化，请刷新后重试");
        }
        repository.audit("REBIND", target.agentId(), target.viewerUserId(), operatorUserId, nextVersion, normalizedReason);
        return bindingView();
    }

    @Transactional(readOnly = true)
    public Optional<PublicBinding> currentPublicBinding() {
        return repository.findSingleton()
                .filter(DisplayObserverBindingRepository.BindingRow::enabled)
                .filter(this::validTarget)
                .filter(binding -> binding.agentId() != null && binding.viewerUserId() != null)
                .map(binding -> new PublicBinding(binding.publicId(), binding.agentId(),
                        binding.viewerUserId(), binding.version()));
    }

    @Transactional(readOnly = true)
    public Optional<PublicBinding> resolvePublicId(String publicId) {
        if (publicId == null || !PUBLIC_ID_PATTERN.matcher(publicId).matches()) return Optional.empty();
        return repository.findByPublicId(publicId)
                .filter(DisplayObserverBindingRepository.BindingRow::enabled)
                .filter(this::validTarget)
                .filter(binding -> binding.agentId() != null && binding.viewerUserId() != null)
                .map(binding -> new PublicBinding(binding.publicId(), binding.agentId(),
                        binding.viewerUserId(), binding.version()));
    }

    /** Prevents the bound showcase BOT from entering automatic dispatch while it is assigned to the public view. */
    @Transactional
    public void requireManualBehaviorIfBound(long viewerUserId, String requestedMode) {
        if (viewerUserId <= 0 || requestedMode == null || !"AUTOMATIC".equalsIgnoreCase(requestedMode)) return;
        if (!repository.lockViewerBehavior(viewerUserId)) return;
        if (repository.isBoundViewer(viewerUserId)) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BOT_MUST_REMAIN_MANUAL",
                    "该托绑定为开奖页展示账户，解除绑定前不能启用自动行为");
        }
    }

    private DisplayObserverBindingRepository.BindingRow lockBinding(long expectedVersion) {
        DisplayObserverBindingRepository.BindingRow current = repository.findSingletonForUpdate()
                .orElseThrow(() -> BusinessException.notFound(
                        "DISPLAY_OBSERVER_BINDING_NOT_FOUND", "开奖页只读入口尚未绑定"));
        if (expectedVersion != current.version()) {
            throw BusinessException.conflict("DISPLAY_OBSERVER_BINDING_VERSION_CONFLICT", "设置已变化，请刷新后重试");
        }
        return current;
    }

    private BindingView bindingView() {
        return repository.findDetails().map(details -> {
            boolean targetValid = validTarget(details);
            return new BindingView(true, details.enabled(), targetValid, details.version(),
                    details.agentId(), details.machineUsername() == null
                            ? details.machineUsernameSnapshot() : details.machineUsername(),
                    details.machineDisplayName() == null
                            ? details.machineDisplayNameSnapshot() : details.machineDisplayName(),
                    details.groupDisplayName() == null
                            ? details.groupDisplayNameSnapshot() : details.groupDisplayName(),
                    details.viewerUserId(), details.viewerDisplayName() == null
                            ? details.viewerDisplayNameSnapshot() : details.viewerDisplayName(),
                    details.publicId(), targetValid ? null : invalidReason(details));
        }).orElseGet(() -> new BindingView(false, false, false, 0,
                null, null, null, null, null, null, null, null));
    }

    private boolean validTarget(DisplayObserverBindingRepository.BindingRow binding) {
        return repository.isValidBinding(binding.agentId(), binding.viewerUserId(), binding.groupIdSnapshot());
    }

    private boolean validTarget(DisplayObserverBindingRepository.BindingDetails binding) {
        return repository.isValidBinding(binding.agentId(), binding.viewerUserId(), binding.groupIdSnapshot());
    }

    private static String invalidReason(DisplayObserverBindingRepository.BindingDetails details) {
        if (details.agentId() == null) return "展示机器不存在或已删除";
        if (details.viewerUserId() == null) return "展示托不存在或已删除";
        if (details.machineStatus() == null || details.machineDeleted()) return "展示机器不存在或已删除";
        if (details.systemOwned() || details.groupId() == null || details.groupDeleted()
                || !"ACTIVE".equals(details.groupStatus()) || !"ACTIVE".equals(details.groupUserStatus())) {
            return "展示机器所属子账号无效";
        }
        if (details.groupExpiresAt() != null
                && !details.groupExpiresAt().isAfter(java.time.Instant.now())) {
            return "展示机器所属子账号已过期";
        }
        if (details.groupId() != details.groupIdSnapshot()) return "展示机器所属子账号已变更";
        if (!"ACTIVE".equals(details.machineStatus())
                || (details.machineExpiresAt() != null && !details.machineExpiresAt().isAfter(java.time.Instant.now()))) {
            return "展示机器已停用或过期";
        }
        if (!details.au8Enabled()) return "展示机器未启用 AU8";
        if (details.viewerAgentId() == null) {
            return "展示托不存在";
        }
        if (!Objects.equals(details.agentId(), details.viewerAgentId())) return "展示托不属于绑定机器";
        if (!"ACTIVE".equals(details.viewerStatus()) || !"ACTIVE".equals(details.viewerAccountStatus())) {
            return "展示托已停用或删除";
        }
        if (!"BOT".equals(details.playerKind())) return "展示账户已不是托";
        if (!"MANUAL".equals(details.runMode()) || !Boolean.FALSE.equals(details.behaviorEnabled())) {
            return "展示托自动行为已开启";
        }
        return "展示绑定状态无效";
    }

    private void requirePlatformPermission(long operatorUserId, String permission) {
        if (operatorUserId <= 0 || !permissionService.findRoleCodes(operatorUserId).contains("ADMIN")
                || !permissionService.hasPermission(operatorUserId, permission)) {
            throw BusinessException.forbidden("DISPLAY_OBSERVER_ADMIN_FORBIDDEN", "只有超级管理员可以管理开奖页只读入口");
        }
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500
                || SENSITIVE_REASON_PATTERN.matcher(reason).find()) {
            throw BusinessException.badRequest("DISPLAY_OBSERVER_REASON_INVALID", "请填写有效原因（1 到 500 字）");
        }
        return reason.trim();
    }

    private String newPublicId() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record PublicBinding(String publicId, long agentId, long viewerUserId, long version) {
    }

    public record BindingView(boolean configured, boolean enabled, boolean valid, long version,
                              Long agentId, String machineUsername, String machineDisplayName,
                              String groupDisplayName, Long viewerUserId, String viewerDisplayName,
                              String publicId, String invalidReason) {
    }
}
