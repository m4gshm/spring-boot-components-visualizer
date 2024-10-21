package service1.service.external.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import static java.util.Objects.requireNonNull;

@Service
@RequiredArgsConstructor
public class Service2LegacyImpl implements Service2Api {

    private final RestTemplate restTemplate;
    private final RestOperations restOperations;
    private final String url = "http://service2";

    @Override
    public String get(String operation, Integer id) {
        return restTemplate.getForObject("http://service2/" + requireNonNull(operation, "operation") + "/" +
                requireNonNull(id, "id"), String.class);
    }

    public String getDeprecated(Integer id) {
        return get("load-deprecated", id);
    }

    public String get2() {
        return restOperations.getForObject(url, String.class);
    }
}
