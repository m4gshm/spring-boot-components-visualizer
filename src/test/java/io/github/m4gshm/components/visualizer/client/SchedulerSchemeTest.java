package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.ComponentsExtractor;
import io.github.m4gshm.components.visualizer.PlantUmlTextFactory;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.function.BiFunction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.util.StreamUtils.copyToByteArray;

@SpringBootTest(classes = SchedulerSchemeTest.SchedulerService.class)
public class SchedulerSchemeTest {

    @Autowired
    ConfigurableApplicationContext applicationContext;

    @Test
    public void test() throws IOException {
        var componentsExtractor = new ComponentsExtractor(applicationContext, ComponentsExtractor.Options.DEFAULT);
        var components = componentsExtractor.getComponents(SchedulerSchemeTest.SchedulerService.class);
        var plantUmlTextFactory = new PlantUmlTextFactory("test-app", PlantUmlTextFactory.Options.DEFAULT);
        var schema = plantUmlTextFactory.create(components);
        var expectedSchema = new String(copyToByteArray(SchedulerSchemeTest.class
                .getResourceAsStream("/SchedulerSchemeTest.puml")), UTF_8);
        assertEquals(expectedSchema, schema);
    }

    @Service
    @RequiredArgsConstructor
    public static class SchedulerService implements SchedulingConfigurer, Runnable {
        public static final int _1_MIN = 60 * 1000;
        public static final int _1_HOUR = 1000 * 60 * 60;
        public static final int _1_SEC = 1000;
        private final SchedulerService s = this;
        private final Runnable runnable = getCall();

        @Scheduled(cron = "* * 1 * * *")
        public void getEvery5Min() {
            call();
        }

        @Scheduled(fixedDelay = _1_HOUR + _1_MIN + _1_SEC + 500)
        public void getEvery1Hour() {
            call();
        }

        private void call() {
            System.out.println("call");
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
            taskRegistrar.addFixedRateTask(new IntervalTask(this::call, getHoursMillis(25)));
            BiFunction<Runnable, Long, IntervalTask> taskBuilder = IntervalTask::new;
            taskRegistrar.addFixedRateTask(taskBuilder.apply(this::call, getHoursMillis(21)));
            taskRegistrar.addFixedRateTask(getRunnable(), MINUTES.toMillis(12));

            taskRegistrar.addCronTask(getCronTask());
            taskRegistrar.addCronTask(new Runnable() {
                @Override
                public void run() {
                    SchedulerService.Utils.getCall2(SchedulerService.this).run();
                }
            }, "* * * 3 * *");

            taskRegistrar.addCronTask(new Runnable() {
                @Override
                public void run() {
                    getCall3(SchedulerService.this::run).run();
                }
            }, "* * * 4 * *");

            taskRegistrar.addFixedDelayTask(getCall(), 1000);
            taskRegistrar.addFixedDelayTask(this::call, 2000);
            taskRegistrar.addFixedDelayTask(SchedulerService.Utils.getCall2(s), 3000);
            taskRegistrar.addFixedDelayTask(this, 4000);
            taskRegistrar.addFixedDelayTask(runnable, 5000);
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
            static Runnable getCall2(SchedulerService s) {
                return s::call;
            }
        }
    }

}
