package service1.service.scheduled;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;
import service1.db.jpa.UserRepository;
import service1.service.external.jms.JmsQueueService;
import service1.service.external.rest.Service2FeignClient;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.HOURS;

@Service
@RequiredArgsConstructor
public class PeriodUpdateService implements SchedulingConfigurer, Runnable {
    public static final int _1_MIN = 60 * 1000;
    public static final int _1_HOUR = 1000 * 60 * 60;
    public static final int _1_SEC = 1000;
    private final Service2FeignClient service2FeignClient;
    private final JmsQueueService jmsQueueService;
    private final UserRepository userRepository;
    private final PeriodUpdateService s = this;

    @Scheduled(fixedDelay = _1_HOUR + _1_MIN + _1_SEC + 500)
    public void getEvery1Hour() {
        call();
    }

    private void call() {
        for (var userEntity : userRepository.findAll()) {
            var id = userEntity.getId();
            var someInfo = service2FeignClient.get(Integer.valueOf(id));
            jmsQueueService.sendMessage(someInfo, null, null, getJmsQueue());
        }
    }

    private String getJmsQueue() {
        var jmsQueueService1 = jmsQueueService;
        requireNonNull(jmsQueueService1);
        return jmsQueueService1.wrap("Queue2");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedRateTask(new IntervalTask(this::call, getHoursMillis(25)));
        taskRegistrar.addCronTask(getCronTask());
        taskRegistrar.addFixedDelayTask(getCall(), 1000);
    }

    private long getHoursMillis(int duration) {
        return HOURS.toMillis(duration);
    }

    private CronTask getCronTask() {
        return new CronTask(this::call, "* * * 2 * *");
    }

    private Runnable getCall() {
        return this::call;
    }

    @Override
    public void run() {

    }

}
