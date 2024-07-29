package service1.api.http;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service1.service.external.rest.Service2Api;

@RequestMapping("/api/v1")
@RestController
@RequiredArgsConstructor
public class MainController {
    private final Service2Api service2Api;

    @GetMapping("/req")
    public String get() {
        return "";
    }

    @RequestMapping
    public String makeAll() {
        return "";
    }
}
