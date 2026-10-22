package com.xupan.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityConfigTest {

    @Test
    void hby019SourceDocumentsAllowSameOriginFraming() throws Exception {
        var writer = SecurityConfig.frameOptionsWriter();
        var panelRequest = request("/hby019-demo/source/account-panel.html");
        var panelResponse = new MockHttpServletResponse();

        writer.writeHeaders(panelRequest, panelResponse);

        assertEquals("SAMEORIGIN", panelResponse.getHeader("X-Frame-Options"));

        var consoleResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/source/console.html"), consoleResponse);
        assertEquals("SAMEORIGIN", consoleResponse.getHeader("X-Frame-Options"));

        var nestedAssetResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/source/static/xz/admin/main.css"), nestedAssetResponse);
        assertEquals("SAMEORIGIN", nestedAssetResponse.getHeader("X-Frame-Options"));

        var pageResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/room"), pageResponse);
        assertEquals("DENY", pageResponse.getHeader("X-Frame-Options"));

        var nestedPathResponse = new MockHttpServletResponse();
        writer.writeHeaders(request("/hby019-demo/sourceevil/account-panel.html"), nestedPathResponse);
        assertEquals("DENY", nestedPathResponse.getHeader("X-Frame-Options"));
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest();
        request.setRequestURI(path);
        request.setServletPath(path);
        return request;
    }
}
