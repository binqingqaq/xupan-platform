package com.xupan.server.media;

import com.xupan.server.web.BusinessException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AvatarStorageService {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");

    private final AvatarProperties properties;

    public AvatarStorageService(AvatarProperties properties) {
        this.properties = properties;
    }

    public StoredAvatar store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("AVATAR_FILE_INVALID", "请选择头像图片");
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw BusinessException.badRequest("AVATAR_FILE_TOO_LARGE", "头像图片不能超过 5 MB");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw BusinessException.badRequest("AVATAR_FILE_TYPE_INVALID", "头像只支持 JPG、PNG、GIF 或 WebP 图片");
        }
        if (!isReadableImage(file, contentType)) {
            throw BusinessException.badRequest("AVATAR_FILE_INVALID", "头像图片内容无法读取");
        }
        Path directory = storageDirectory();
        String key = UUID.randomUUID() + "." + extension;
        Path target = directory.resolve(key).normalize();
        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target);
            }
            return new StoredAvatar(key, contentType, file.getSize());
        } catch (IOException exception) {
            throw new IllegalStateException("头像文件保存失败", exception);
        }
    }

    public Resource load(String avatarKey) {
        if (avatarKey == null || !avatarKey.matches("[0-9a-fA-F-]{36}\\.(jpg|png|gif|webp)")) {
            throw BusinessException.notFound("AVATAR_NOT_FOUND", "头像不存在");
        }
        Path target = storageDirectory().resolve(avatarKey).normalize();
        if (!target.startsWith(storageDirectory()) || !Files.isRegularFile(target)) {
            throw BusinessException.notFound("AVATAR_NOT_FOUND", "头像不存在");
        }
        return new FileSystemResource(target);
    }

    public MediaType mediaType(String avatarKey) {
        String extension = avatarKey.substring(avatarKey.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private Path storageDirectory() {
        return properties.getStorageDir().toAbsolutePath().normalize();
    }

    private static boolean isReadableImage(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            if ("image/webp".equals(contentType)) {
                byte[] header = input.readNBytes(12);
                return header.length == 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            }
            BufferedImage image = ImageIO.read(input);
            return image != null && image.getWidth() > 0 && image.getHeight() > 0;
        } catch (IOException exception) {
            return false;
        }
    }

    public record StoredAvatar(String avatarKey, String contentType, long sizeBytes) {
    }
}
