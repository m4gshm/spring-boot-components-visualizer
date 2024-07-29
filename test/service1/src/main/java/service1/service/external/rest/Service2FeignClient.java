package service1.service.external.rest;

import org.springframework.cloud.openfeign.FeignClient;

@FeignClient(name = "service2FeignClient", url = "service2")
public interface Service2FeignClient extends AbstractService2FeignClient {

}
