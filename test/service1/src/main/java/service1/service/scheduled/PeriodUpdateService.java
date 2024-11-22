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

import java.util.function.BiFunction;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static service1.service.scheduled.PeriodUpdateService.Utils.getCall2;

@Service
@RequiredArgsConstructor
public class PeriodUpdateService implements SchedulingConfigurer, Runnable {
    private final Service2FeignClient service2FeignClient;
    private final JmsQueueService jmsQueueService;
    private final UserRepository userRepository;
    private final PeriodUpdateService s = this;
    private final Runnable runnable = getCall();

    @Scheduled(cron = "* * 1 * * *")
    public void getEvery5Min() {
        call();
    }

    @Scheduled(fixedDelay = 1000 * 60)
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
        taskRegistrar.addFixedRateTask(new IntervalTask(this::call, getHoursMillis(20)));
//        BiFunction<Runnable, Long, IntervalTask> taskBuilder = IntervalTask::new;
//        taskRegistrar.addFixedRateTask(taskBuilder.apply(this::call, getHoursMillis(21)));
//        taskRegistrar.addFixedRateTask(getRunnable(), MINUTES.toMillis(12));
//        taskRegistrar.addCronTask(getCronTask());
//        taskRegistrar.addCronTask(new Runnable() {
//            @Override
//            public void run() {
//                getCall2(PeriodUpdateService.this).run();
//            }
//        }, "* * * 3 * *");
//
//        taskRegistrar.addCronTask(new Runnable() {
//            @Override
//            public void run() {
//                getCall3(PeriodUpdateService.this::run).run();
//            }
//        }, "* * * 4 * *");
//
//        taskRegistrar.addFixedDelayTask(getCall(), 1000);
//        taskRegistrar.addFixedDelayTask(this::call, 2000);
//        taskRegistrar.addFixedDelayTask(getCall2(s), 3000);
//        taskRegistrar.addFixedDelayTask(this, 4000);
//        taskRegistrar.addFixedDelayTask(runnable, 5000);
    }

    private Runnable getCall3(Runnable runnable) {
        return runnable::run;
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

    private Runnable getRunnable() {
        return new Runnable() {
            @Override
            public void run() {
                s.call();
            }
        };
    }

    @Override
    public void run() {

    }

    static class Utils {
        static Runnable getCall2(PeriodUpdateService s) {
            return s::call;
        }
    }
}
