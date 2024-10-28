package service1.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import service1.db.jpa.SimpleEntityRepository;
import service1.db.mongo.DocumentRepository;
import service1.service.external.jms.JmsQueueService;
import service1.service.external.rest.AbstractService2FeignClient;
import service1.service.external.rest.Service2Api;
import service1.service.external.rest.Service2FeignClient;

import java.util.List;

import static java.util.Objects.requireNonNull;

@Service
public class CoreService {

    private final Service2FeignClient service2FeignClient;
    private final Service2Api service2LegacyImpl;
    private final AbstractService2FeignClient standaloneService2Api;
    private final SimpleEntityRepository simpleEntityRepository;
    private final DocumentRepository documentRepository;
    private final JmsQueueService jmsQueueService;


    public CoreService(Service2FeignClient service2FeignClient,
                       Service2Api service2LegacyImpl,
                       @Qualifier("standaloneService2Api") AbstractService2FeignClient standaloneService2Api,
                       SimpleEntityRepository simpleEntityRepository, DocumentRepository documentRepository,
                       JmsQueueService jmsQueueService) {
        this.service2FeignClient = service2FeignClient;
        this.service2LegacyImpl = service2LegacyImpl;
        this.standaloneService2Api = standaloneService2Api;
        this.simpleEntityRepository = simpleEntityRepository;
        this.documentRepository = documentRepository;
        this.jmsQueueService = jmsQueueService;
    }

    public String get(String strId) {
        var integerId = Integer.valueOf(strId);
        var result = service2FeignClient.get(integerId);
        if (result == null) {
            var intId = requireNonNull(integerId);
            var firstLoad = service2LegacyImpl.get("load", ++intId);
            if (firstLoad != null) {
                return firstLoad;
            } else {
                return service2LegacyImpl.get("load2", -(intId + 2));
            }
        } else {
            return standaloneService2Api.get(integerId);
        }
    }

    public String makeAll() {
        var jmsQueue1 = getJmsQueue();
        List.of(0, 1).forEach(i -> jmsQueueService.sendMessage(null, null, null, jmsQueue1 + i));
        return null;
    }

    private String getJmsQueue() {
        JmsQueueService jmsQueueService1 = jmsQueueService;
        requireNonNull(jmsQueueService1);
        return jmsQueueService1.wrap("Queue");
    }
}
