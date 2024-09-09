package service1.api.http;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import service1.service.CoreService;

@RequestMapping("/api/v1")
@RestController
@RequiredArgsConstructor
public class MainController {
    private final CoreService coreService;

    @GetMapping("/load/{id}")
    public String get(@PathVariable String id) {
        return coreService.get(id);
    }

    @PostMapping("/put/{id}")
    public String save(@PathVariable String id) {
        return coreService.get(id);
    }

    @RequestMapping
    public String makeAll() {
        return coreService.makeAll();
    }
}
