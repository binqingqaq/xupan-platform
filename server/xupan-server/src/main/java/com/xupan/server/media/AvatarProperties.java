package com.xupan.server.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "xupan.avatar")
public class AvatarProperties {

    private Path storageDir = Path.of("var/uploads/avatars");
    private long maxSizeBytes = 5 * 1024 * 1024;
    private boolean autoGenerate = true;

    public Path getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(Path storageDir) {
        this.storageDir = storageDir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public boolean isAutoGenerate() {
        return autoGenerate;
    }

    public void setAutoGenerate(boolean autoGenerate) {
        this.autoGenerate = autoGenerate;
    }
}
