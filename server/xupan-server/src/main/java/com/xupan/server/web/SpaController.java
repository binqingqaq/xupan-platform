package com.xupan.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
            "/login", "/login/",
            "/forbidden", "/forbidden/",
            "/display", "/display/",
            "/display/mobile", "/display/mobile/",
            "/room", "/room/",
            "/admin", "/admin/",
            "/admin/users", "/admin/users/"
    })
    public String forwardToFrontend() {
        return "forward:/index.html";
    }
}
