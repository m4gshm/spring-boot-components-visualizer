package service1.service.external.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

public interface AbstractService2FeignClient {
    @GetMapping("/{id}")
    String get(@PathVariable Integer id);
}
