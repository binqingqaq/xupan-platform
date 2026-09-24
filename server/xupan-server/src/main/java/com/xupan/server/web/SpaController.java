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
            "/player-login", "/player-login/",
            "/console", "/console/",
            "/console/operations", "/console/operations/",
            "/console/users", "/console/users/",
            "/console/players", "/console/players/",
            "/console/robots", "/console/robots/",
            "/admin", "/admin/",
            "/admin/operations", "/admin/operations/",
            "/admin/users", "/admin/users/",
            "/admin/test-players", "/admin/test-players/",
            "/admin/robots", "/admin/robots/"
    })
    public String forwardToFrontend() {
        return "forward:/index.html";
    }

    @GetMapping({"/33/{linkPath}", "/33/{linkPath}/"})
    public String forwardPlayerLink() {
        return "forward:/index.html";
    }
}
