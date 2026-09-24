package com.xupan.server.display;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class HttpExternalLotteryListClient implements ExternalLotteryListClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36";

    private final MobileDisplayProperties properties;
    private final HttpClient httpClient;

    public HttpExternalLotteryListClient(MobileDisplayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String fetchHotLotteryList() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MOBILE_DISPLAY_EXTERNAL_DISABLED");
        }
        URI uri = URI.create(trimTrailingSlash(properties.getBaseUrl()) + properties.getHotLotteryPath());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", USER_AGENT)
                .header("Referer", properties.getReferer())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "MOBILE_DISPLAY_EXTERNAL_HTTP_" + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MOBILE_DISPLAY_EXTERNAL_INTERRUPTED", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("MOBILE_DISPLAY_EXTERNAL_UNAVAILABLE", exception);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
