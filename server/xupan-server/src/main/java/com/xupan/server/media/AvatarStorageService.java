package com.xupan.server.media;

import com.xupan.server.web.BusinessException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");
    private static final List<String> DICEBEAR_STYLES = List.of(
            "adventurer", "adventurer-neutral", "avataaars", "avataaars-neutral",
            "big-ears", "big-ears-neutral", "big-smile", "bottts", "lorelei",
            "notionists", "open-peeps", "personas", "pixel-art", "thumbs");
    private static final HttpClient DICEBEAR_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public StoredAvatar storeGenerated(String seed) {
        String normalizedSeed = seed == null || seed.isBlank()
                ? UUID.randomUUID().toString() : seed.trim();
        String requestSeed = normalizedSeed + "-" + UUID.randomUUID();
        try {
            String style = DICEBEAR_STYLES.get(RANDOM.nextInt(DICEBEAR_STYLES.size()));
            String encodedSeed = URLEncoder.encode(requestSeed, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dicebear.com/10.x/" + style
                            + "/png?size=256&seed=" + encodedSeed))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = DICEBEAR_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() != 200 || body == null || body.length == 0
                    || body.length > properties.getMaxSizeBytes() || !isReadableImage(body)) {
                throw new IOException("DiceBear returned an invalid image");
            }
            return storeBytes(body, "image/png", "png");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("随机头像下载被中断，使用本地兜底头像");
            return storeFallback(normalizedSeed);
        } catch (IOException | RuntimeException exception) {
            log.warn("随机头像下载失败，使用本地兜底头像: {}", exception.getMessage());
            return storeFallback(normalizedSeed);
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

    private StoredAvatar storeBytes(byte[] bytes, String contentType, String extension) throws IOException {
        if (bytes.length > properties.getMaxSizeBytes()) {
            throw new IOException("头像文件过大");
        }
        Path directory = storageDirectory();
        String key = UUID.randomUUID() + "." + extension;
        Path target = directory.resolve(key).normalize();
        Files.createDirectories(directory);
        Files.write(target, bytes);
        return new StoredAvatar(key, contentType, bytes.length);
    }

    private StoredAvatar storeFallback(String seed) {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color background = Color.getHSBColor(Math.floorMod(seed.hashCode(), 360) / 360f, 0.62f, 0.82f);
            graphics.setColor(background);
            graphics.fillRect(0, 0, 256, 256);
            graphics.setColor(new Color(255, 255, 255, 220));
            graphics.fillOval(45, 35, 166, 166);
            graphics.setColor(background.darker());
            graphics.fillOval(82, 91, 18, 18);
            graphics.fillOval(156, 91, 18, 18);
            graphics.fillRoundRect(91, 132, 74, 22, 11, 11);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            String initial = seed.isBlank() ? "?" : seed.substring(0, 1).toUpperCase(Locale.ROOT);
            int width = graphics.getFontMetrics().stringWidth(initial);
            graphics.drawString(initial, (256 - width) / 2, 235);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("本地头像生成失败");
            }
            return storeBytes(output.toByteArray(), "image/png", "png");
        } catch (IOException exception) {
            throw new IllegalStateException("本地头像保存失败", exception);
        }
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

    private static boolean isReadableImage(byte[] bytes) {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(input);
            return image != null && image.getWidth() > 0 && image.getHeight() > 0;
        } catch (IOException exception) {
            return false;
        }
    }

    public record StoredAvatar(String avatarKey, String contentType, long sizeBytes) {
    }
}
