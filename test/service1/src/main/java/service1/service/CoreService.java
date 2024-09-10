package service1.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import service1.db.jpa.SimpleEntityRepository;
import service1.db.mongo.DocumentRepository;
import service1.service.external.jms.JmsQueueService;
import service1.service.external.rest.AbstractService2FeignClient;
import service1.service.external.rest.Service2Api;
import service1.service.external.rest.Service2FeignClient;

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

    public String get(String id) {
        return service2LegacyImpl.get("load", id);
    }

    public String makeAll() {
        jmsQueueService.sendMessage(null, null, null, jmsQueueService.wrap("jmsQueue1"));
        return null;
    }
}
