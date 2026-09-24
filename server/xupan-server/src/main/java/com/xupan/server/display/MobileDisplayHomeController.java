package com.xupan.server.display;

import com.xupan.server.web.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/display/mobile")
public class MobileDisplayHomeController {

    private final MobileDisplayHomeService service;

    public MobileDisplayHomeController(MobileDisplayHomeService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public MobileDisplayHomeResponse home() {
        return service.getHome();
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of(
                        "code", exception.code(),
                        "message", exception.publicMessage()));
    }
}
