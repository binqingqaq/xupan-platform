package com.xupan.server.media;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class AvatarPresetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AvatarPresetRepository repository;
    private final UserRepository userRepository;

    public AvatarPresetService(AvatarPresetRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional
    public String allocateForUser(long userId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<String> candidates = new ArrayList<>(repository.findAvailableKeys());
            if (candidates.isEmpty()) {
                throw BusinessException.conflict("AVATAR_POOL_EXHAUSTED", "头像池已满，请先删除或更换已占用头像");
            }
            Collections.shuffle(candidates, RANDOM);
            for (String candidate : candidates) {
                if (repository.assignIfAvailable(candidate, userId) == 1) {
                    return candidate;
                }
            }
        }
        throw BusinessException.conflict("AVATAR_POOL_EXHAUSTED", "头像池已满，请先删除或更换已占用头像");
    }

    @Transactional
    public String replaceForUser(long userId, String targetAvatarKey) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在"));
        String normalizedTarget = requiredPresetKey(targetAvatarKey);
        String currentAvatarKey = user.avatarKey();
        if (normalizedTarget.equals(currentAvatarKey)) {
            return currentAvatarKey;
        }

        AvatarPresetRepository.PresetRow target = repository.findByKey(normalizedTarget)
                .orElseThrow(() -> BusinessException.badRequest("AVATAR_PRESET_INVALID", "头像不在可选池中"));
        if (target.assignedUserId() != null && target.assignedUserId() != userId) {
            throw BusinessException.conflict("AVATAR_PRESET_ASSIGNED", "该头像已被其他玩家或托使用");
        }
        if (isPresetKey(currentAvatarKey) && !normalizedTarget.equals(currentAvatarKey)) {
            repository.releaseIfAssignedTo(userId, currentAvatarKey);
        }
        if (target.assignedUserId() == null && repository.assignIfAvailable(normalizedTarget, userId) != 1) {
            throw BusinessException.conflict("AVATAR_PRESET_ASSIGNED", "该头像已被其他玩家或托使用");
        }
        if (userRepository.updateAvatarKey(userId, normalizedTarget) != 1) {
            throw BusinessException.notFound("PLAYER_NOT_FOUND", "玩家不存在");
        }
        return normalizedTarget;
    }

    @Transactional
    public void release(long userId) {
        repository.releaseByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<AvatarPresetOption> list(Long selectedUserId) {
        String selectedAvatarKey = selectedUserId == null ? null
                : userRepository.findById(selectedUserId).map(UserAccount::avatarKey).orElse(null);
        return repository.findAll().stream()
                .map(row -> new AvatarPresetOption(
                        row.avatarKey(),
                        "/avatars/presets/" + row.avatarKey(),
                        row.displayOrder(),
                        row.assignedUserId() == null,
                        row.assignedUserId(),
                        row.assignedDisplayName(),
                        row.avatarKey().equals(selectedAvatarKey)))
                .toList();
    }

    public boolean isPresetKey(String avatarKey) {
        return avatarKey != null && avatarKey.matches("preset-\\d{2}\\.(jpg|png)");
    }

    private String requiredPresetKey(String avatarKey) {
        if (avatarKey == null || avatarKey.isBlank()) {
            throw BusinessException.badRequest("AVATAR_PRESET_INVALID", "头像标识不能为空");
        }
        String normalized = avatarKey.trim();
        if (!isPresetKey(normalized) || repository.findByKey(normalized).isEmpty()) {
            throw BusinessException.badRequest("AVATAR_PRESET_INVALID", "头像不在可选池中");
        }
        return normalized;
    }

    public record AvatarPresetOption(String key, String url, int displayOrder, boolean available,
                                     Long assignedUserId, String assignedDisplayName, boolean selected) {
    }
}

