package com.xupan.server.media;

import com.xupan.server.auth.domain.AuthenticatedUser;
import com.xupan.server.auth.repository.UserRepository;
import com.xupan.server.auth.service.AuthenticationService;
import com.xupan.server.robot.service.RobotAdminService;
import com.xupan.server.system.service.UserAdminService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class AvatarController {

    private final AvatarStorageService storageService;
    private final UserRepository userRepository;
    private final UserAdminService userAdminService;
    private final RobotAdminService robotAdminService;

    public AvatarController(AvatarStorageService storageService, UserRepository userRepository,
                            UserAdminService userAdminService, RobotAdminService robotAdminService) {
        this.storageService = storageService;
        this.userRepository = userRepository;
        this.userAdminService = userAdminService;
        this.robotAdminService = robotAdminService;
    }

    @GetMapping("/media/avatars/{avatarKey}")
    public ResponseEntity<Resource> get(@PathVariable String avatarKey) {
        Resource resource = storageService.load(avatarKey);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .contentType(storageService.mediaType(avatarKey))
                .body(resource);
    }

    @PutMapping("/me/avatar")
    public AvatarResponse updateMine(Authentication authentication,
                                     @RequestPart("file") MultipartFile file) {
        AuthenticatedUser user = principal(authentication);
        String key = storageService.store(file).avatarKey();
        if (userRepository.updateAvatarKey(user.getUserId(), key) != 1) {
            throw new IllegalStateException("用户头像更新失败");
        }
        return AvatarResponse.of(key);
    }

    @PutMapping("/admin/users/{userId}/avatar")
    public AvatarResponse updateUser(Authentication authentication, @PathVariable long userId,
                                     @RequestPart("file") MultipartFile file) {
        AuthenticatedUser operator = principal(authentication);
        String key = storageService.store(file).avatarKey();
        userAdminService.updateAvatar(userId, key, operator.getUserId());
        return AvatarResponse.of(key);
    }

    @PutMapping("/admin/robots/{robotId}/avatar")
    public AvatarResponse updateRobot(Authentication authentication, @PathVariable long robotId,
                                      @RequestPart("file") MultipartFile file) {
        AuthenticatedUser operator = principal(authentication);
        String key = storageService.store(file).avatarKey();
        robotAdminService.updateAvatar(operator.getUserId(), robotId, key);
        return AvatarResponse.of(key);
    }

    private static AuthenticatedUser principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationService.AuthenticationFailure("AUTH_UNAUTHENTICATED");
        }
        return user;
    }

    public record AvatarResponse(String avatarKey, String url) {
        static AvatarResponse of(String avatarKey) {
            return new AvatarResponse(avatarKey, "/api/media/avatars/" + avatarKey);
        }
    }
}
