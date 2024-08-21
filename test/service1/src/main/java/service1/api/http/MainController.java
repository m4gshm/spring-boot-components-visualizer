package service1.api.http;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service1.service.CoreService;

@RequestMapping("/api/v1")
@RestController
@RequiredArgsConstructor
public class MainController {
    private final CoreService coreService;

    @GetMapping("/load")
    public String get() {
        return coreService.get();
    }

    @PostMapping("/put")
    public String save() {
        return coreService.get();
    }

    @RequestMapping
    public String makeAll() {
        return coreService.makeAll();
    }
}
