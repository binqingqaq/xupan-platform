package com.xupan.server.display;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;

@Component
@ConfigurationProperties(prefix = "xupan.display.mobile.macau")
public class MobileDisplayMacauProperties {

    private boolean enabled = true;
    private String baseUrl = "https://h9.wanpz.com";
    private String newScriptPath = "/script/12359/temps_result_1.js?v=37ba719b";
    private String oldScriptPath = "/script/12359/temps_result_3.js?v=37ba719b";
    private Duration connectTimeout = Duration.ofSeconds(4);
    private Duration requestTimeout = Duration.ofSeconds(12);
    private Duration cacheTtl = Duration.ofSeconds(30);
    private LocalTime newDrawTime = LocalTime.of(21, 30);
    private LocalTime oldDrawTime = LocalTime.of(21, 32);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getNewScriptPath() {
        return newScriptPath;
    }

    public void setNewScriptPath(String newScriptPath) {
        this.newScriptPath = newScriptPath;
    }

    public String getOldScriptPath() {
        return oldScriptPath;
    }

    public void setOldScriptPath(String oldScriptPath) {
        this.oldScriptPath = oldScriptPath;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public LocalTime getNewDrawTime() {
        return newDrawTime;
    }

    public void setNewDrawTime(LocalTime newDrawTime) {
        this.newDrawTime = newDrawTime;
    }

    public LocalTime getOldDrawTime() {
        return oldDrawTime;
    }

    public void setOldDrawTime(LocalTime oldDrawTime) {
        this.oldDrawTime = oldDrawTime;
    }
}
