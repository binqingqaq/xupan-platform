package com.xupan.server.display;

import com.xupan.server.web.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/display/mobile")
public class MobileDisplayHomeController {

    private final MobileDisplayHomeService service;
    private final MobileDisplayAu8Service au8Service;
    private final MobileDisplayMacauService macauService;

    public MobileDisplayHomeController(MobileDisplayHomeService service,
                                       MobileDisplayAu8Service au8Service,
                                       MobileDisplayMacauService macauService) {
        this.service = service;
        this.au8Service = au8Service;
        this.macauService = macauService;
    }

    @GetMapping("/home")
    public MobileDisplayHomeResponse home() {
        return service.getHome();
    }

    @GetMapping("/macau")
    public MobileDisplayHomeResponse macau() {
        return macauService.getDraws();
    }

    @GetMapping("/au8")
    public MobileDisplayAu8Service.MobileDisplayAu8Response au8() {
        return au8Service.getDraw();
    }

    @GetMapping("/au8/history")
    public MobileDisplayAu8Service.HistoryResponse au8History(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return au8Service.getHistory(page, pageSize);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of(
                        "code", exception.code(),
                        "message", exception.publicMessage()));
    }
}
