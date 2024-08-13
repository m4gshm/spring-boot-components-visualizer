package service1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.map.repository.config.EnableMapRepositories;
import org.springframework.jms.annotation.EnableJms;
import service1.db.jpa.SimpleEntityRepository;
import service1.db.jpa.model.SimpleEntity;

@EnableFeignClients
@EnableJms
@EnableMapRepositories
@EnableJpaRepositories(basePackageClasses = SimpleEntityRepository.class)
@EntityScan(basePackageClasses = SimpleEntity.class)
@SpringBootApplication(proxyBeanMethods = false)
public class Service1Application {
    public static void main(String[] args) {
        SpringApplication.run(Service1Application.class, args);
    }
}
