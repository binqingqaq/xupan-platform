package com.xupan.server.media;

import com.xupan.server.auth.domain.UserAccount;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarPresetServiceTest {

    @Mock
    private AvatarPresetRepository repository;
    @Mock
    private UserRepository userRepository;

    private AvatarPresetService service;

    @BeforeEach
    void setUp() {
        service = new AvatarPresetService(repository, userRepository);
    }

    @Test
    void allocatesAnAvailablePreset() {
        when(repository.findAvailableKeys()).thenReturn(List.of("preset-02.jpg"));
        when(repository.assignIfAvailable("preset-02.jpg", 9L)).thenReturn(1);

        assertThat(service.allocateForUser(9L)).isEqualTo("preset-02.jpg");
    }

    @Test
    void failsWhenPoolIsExhausted() {
        when(repository.findAvailableKeys()).thenReturn(List.of());

        assertThatThrownBy(() -> service.allocateForUser(9L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("AVATAR_POOL_EXHAUSTED");
    }

    @Test
    void replacesAvatarAndReleasesTheOldPreset() {
        UserAccount user = new UserAccount(9L, "player-9", "玩家九", "preset-01.jpg",
                "hash", "ACTIVE", 0, null, 0, null, null, "REAL", "wxid_9", "PLAYER_LINK");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(repository.findByKey("preset-02.jpg")).thenReturn(Optional.of(
                new AvatarPresetRepository.PresetRow("preset-02.jpg", 2, null, null, null)));
        when(repository.assignIfAvailable("preset-02.jpg", 9L)).thenReturn(1);
        when(userRepository.updateAvatarKey(9L, "preset-02.jpg")).thenReturn(1);

        assertThat(service.replaceForUser(9L, "preset-02.jpg")).isEqualTo("preset-02.jpg");

        var order = inOrder(repository);
        order.verify(repository).releaseIfAssignedTo(9L, "preset-01.jpg");
        order.verify(repository).assignIfAvailable("preset-02.jpg", 9L);
        verify(userRepository).updateAvatarKey(9L, "preset-02.jpg");
    }

    @Test
    void rejectsAnOccupiedTargetPreset() {
        UserAccount user = new UserAccount(9L, "player-9", "玩家九", null,
                "hash", "ACTIVE", 0, null, 0, null, null, "REAL", "wxid_9", "PLAYER_LINK");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(repository.findByKey("preset-02.jpg")).thenReturn(Optional.of(
                new AvatarPresetRepository.PresetRow("preset-02.jpg", 2, 10L, Instant.now(), "另一个玩家")));

        assertThatThrownBy(() -> service.replaceForUser(9L, "preset-02.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("AVATAR_PRESET_ASSIGNED");
    }
}

