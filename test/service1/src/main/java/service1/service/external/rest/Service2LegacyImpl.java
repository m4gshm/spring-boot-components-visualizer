package service1.service.external.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class Service2LegacyImpl implements Service2Api {

    private final RestTemplate restTemplate;
    private final RestOperations restOperations;
    private final String url = "http://service2";

    @Override
    public String get() {
        return restTemplate.getForObject("http://service2/{id}", String.class);
    }

    public String get2() {
        return restOperations.getForObject(url, String.class);
    }
}
