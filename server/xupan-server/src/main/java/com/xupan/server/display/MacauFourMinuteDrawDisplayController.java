package com.xupan.server.display;

import com.xupan.server.platformadmin.service.MacauFourMinuteDrawService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/display/mobile/macau-four-minute")
public class MacauFourMinuteDrawDisplayController {

    private final MacauFourMinuteDrawService service;

    public MacauFourMinuteDrawDisplayController(MacauFourMinuteDrawService service) {
        this.service = service;
    }

    @GetMapping
    public MacauFourMinuteDrawService.DisplayResponse getDraw() {
        return service.getDisplay();
    }
}
