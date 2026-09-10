package com.xupan.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/room", "/admin"})
    public String forwardToFrontend() {
        return "forward:/index.html";
    }
}
