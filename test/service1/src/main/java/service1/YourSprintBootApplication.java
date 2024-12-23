package service1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.scheduling.annotation.EnableScheduling;
import service1.db.jpa.SimpleEntityRepository;
import service1.db.jpa.model.SimpleEntity;
import service1.db.mongo.DocumentRepository;

@EnableFeignClients
@EnableJms
@EnableJpaRepositories(basePackageClasses = SimpleEntityRepository.class)
@EnableMongoRepositories(basePackageClasses = DocumentRepository.class)
@EntityScan(basePackageClasses = SimpleEntity.class)
@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
public class YourSprintBootApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourSprintBootApplication.class, args);
    }
}
