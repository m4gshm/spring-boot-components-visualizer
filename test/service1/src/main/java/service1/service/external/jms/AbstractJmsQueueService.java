package service1.service.external.jms;

public class AbstractJmsQueueService {
    public final String wrap(String queue) {
        return "jms" + queue;
    }

    protected String getJmsQueueEvents2() {
        return new StringBuilder("jmsQueueEvents2").toString();
    }
}
