package service1.config;

import feign.Contract;
import feign.Feign;
import feign.codec.Decoder;
import feign.codec.Encoder;
import org.springframework.cloud.openfeign.FeignClientsConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import service1.service.external.rest.AbstractService2FeignClient;

@Configuration
@Import({FeignClientsConfiguration.class})
public class Service2StandByClientConfig {

    @Bean
    public AbstractService2FeignClient standaloneService2Api(Contract contract, Encoder feignEncoder, Decoder feignDecoder) {
        return Feign.builder().contract(contract).encoder(feignEncoder).decoder(feignDecoder)
                .target(AbstractService2FeignClient.class, "standalone-service2");
    }
}
