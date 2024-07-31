package service1.service.external;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import service1.service.external.rest.Service2Api;
import service1.service.external.rest.Service2FeignClient;

@Service
@RequiredArgsConstructor
public class CoreService {

    private final Service2FeignClient service2FeignClient;
    private final Service2Api service2LegacyImpl;

    public String get() {
        return null;
    }

    public String makeAll() {
        return null;
    }
}
