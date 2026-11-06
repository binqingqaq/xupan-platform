package com.xupan.server.display;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component
public class HttpMobileDisplayMacauClient {

    private static final int MAX_SCRIPT_CHARS = 8 * 1024 * 1024;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36";

    private final MobileDisplayMacauProperties properties;
    private final HttpClient httpClient;

    public HttpMobileDisplayMacauClient(MobileDisplayMacauProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ScriptPayloads fetchScripts() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_DISABLED");
        }

        CompletableFuture<HttpResponse<String>> newScript = httpClient.sendAsync(
                request(properties.getNewScriptPath()),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<String>> oldScript = httpClient.sendAsync(
                request(properties.getOldScriptPath()),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            CompletableFuture.allOf(newScript, oldScript).get(
                    properties.getRequestTimeout().toMillis() + 500,
                    TimeUnit.MILLISECONDS);
            return new ScriptPayloads(body(newScript.join()), body(oldScript.join()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_INTERRUPTED", exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException | RuntimeException exception) {
            newScript.cancel(true);
            oldScript.cancel(true);
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_UNAVAILABLE", exception);
        }
    }

    private HttpRequest request(String path) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank() || path == null || path.isBlank()) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_URL_INVALID");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String resource = path.startsWith("/") ? path : "/" + path;
        return HttpRequest.newBuilder(URI.create(base + resource))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/javascript, text/javascript, */*")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }

    private static String body(HttpResponse<String> response) {
        String value = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_HTTP_" + response.statusCode());
        }
        if (value == null || value.isBlank() || value.length() > MAX_SCRIPT_CHARS) {
            throw new IllegalStateException("MOBILE_DISPLAY_MACAU_EXTERNAL_BODY_INVALID");
        }
        return value;
    }

    public record ScriptPayloads(String newScript, String oldScript) {
    }
}
