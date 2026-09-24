package com.xupan.server.system.service;

import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.system.repository.PlayerQuickBetPreferenceRepository;
import com.xupan.server.web.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class PlayerQuickBetPreferenceService {

    private static final List<BigDecimal> DEFAULT_AMOUNTS = List.of(
            new BigDecimal("50.00"),
            new BigDecimal("100.00"),
            new BigDecimal("200.00"),
            new BigDecimal("500.00"),
            new BigDecimal("1000.00")
    );
    private static final int AMOUNT_COUNT = 5;

    private final PlayerQuickBetPreferenceRepository repository;
    private final UserRepository userRepository;

    public PlayerQuickBetPreferenceService(PlayerQuickBetPreferenceRepository repository,
                                           UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<BigDecimal> getAmounts(long userId) {
        requireActiveUser(userId);
        return repository.findByUserId(userId)
                .map(PlayerQuickBetPreferenceService::normalizeStoredAmounts)
                .orElse(DEFAULT_AMOUNTS);
    }

    @Transactional
    public List<BigDecimal> saveAmounts(long userId, List<BigDecimal> amounts) {
        requireActiveUser(userId);
        List<BigDecimal> normalized = normalizeRequestedAmounts(amounts);
        repository.upsert(userId, normalized);
        return normalized;
    }

    private void requireActiveUser(long userId) {
        if (userId <= 0) {
            throw BusinessException.forbidden("PLAYER_QUICK_BET_FORBIDDEN", "当前账号不能保存快捷下注偏好");
        }
        var user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound(
                        "PLAYER_QUICK_BET_USER_NOT_FOUND", "玩家不存在"));
        if (!"ACTIVE".equals(user.status())) {
            throw BusinessException.forbidden("PLAYER_QUICK_BET_FORBIDDEN", "当前玩家状态不能保存快捷下注偏好");
        }
    }

    private static List<BigDecimal> normalizeRequestedAmounts(List<BigDecimal> amounts) {
        if (amounts == null || amounts.size() != AMOUNT_COUNT) {
            throw BusinessException.badRequest(
                    "PLAYER_QUICK_BET_AMOUNTS_INVALID", "快捷金额必须提供 5 个大于 0 的数值");
        }
        return amounts.stream()
                .map(PlayerQuickBetPreferenceService::normalizeAmount)
                .toList();
    }

    private static List<BigDecimal> normalizeStoredAmounts(List<BigDecimal> amounts) {
        return amounts.stream()
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .toList();
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw BusinessException.badRequest(
                    "PLAYER_QUICK_BET_AMOUNTS_INVALID", "快捷金额必须是大于 0 且最多两位小数的数值");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        int integerDigits = normalized.precision() - normalized.scale();
        if (integerDigits > 12) {
            throw BusinessException.badRequest(
                    "PLAYER_QUICK_BET_AMOUNTS_INVALID", "快捷金额超出允许范围");
        }
        return normalized;
    }
}
