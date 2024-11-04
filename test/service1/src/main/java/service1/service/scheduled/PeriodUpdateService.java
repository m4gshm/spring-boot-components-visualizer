package service1.service.scheduled;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import service1.db.jpa.UserRepository;
import service1.db.jpa.model.UserEntity;
import service1.service.external.jms.JmsQueueService;
import service1.service.external.rest.Service2FeignClient;

import static java.util.Objects.requireNonNull;

@Service
@RequiredArgsConstructor
public class PeriodUpdateService {
    private final Service2FeignClient service2FeignClient;
    private final JmsQueueService jmsQueueService;
    private final UserRepository userRepository;

    @Scheduled(cron = "* * 1 * * *")
    public void getEvery5Min() {
        call();
    }

    @Scheduled(fixedDelay = 1000*60)
    public void getEvery1Hour() {
        call();
    }

    private void call() {
        for (UserEntity userEntity : userRepository.findAll()) {
            var id = userEntity.getId();
            var someInfo = service2FeignClient.get(Integer.valueOf(id));
            jmsQueueService.sendMessage(someInfo, null, null, getJmsQueue());
        }
    }

    private String getJmsQueue() {
        JmsQueueService jmsQueueService1 = jmsQueueService;
        requireNonNull(jmsQueueService1);
        return jmsQueueService1.wrap("Queue2");
    }
}
