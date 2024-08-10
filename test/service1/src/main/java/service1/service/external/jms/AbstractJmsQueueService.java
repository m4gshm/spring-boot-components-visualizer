package service1.service.external.jms;

public class AbstractJmsQueueService {
    protected final String wrap(String jmsQueue) {
        return jmsQueue;
    }

    protected String getJmsQueueEvents2() {
        return new StringBuilder("jmsQueueEvents2").toString();
    }
}
