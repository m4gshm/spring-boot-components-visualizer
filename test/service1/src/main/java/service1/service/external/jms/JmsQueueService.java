package service1.service.external.jms;

import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;

@Service
@RequiredArgsConstructor
public class JmsQueueService extends AbstractJmsQueueService {
    private final JmsTemplate jmsTemplate;
    private final JmsOperations jmsOperations;

    public final void sendMessage(String message, String message2, String message3, String jmsQueue) {
        var jmsQueue2 = wrap("jmsQueue2");
        if (message3 != null)
            jmsTemplate.sendAndReceive(jmsQueue, session -> session.createTextMessage(message3 != null ? message3 : message2));
        else
            jmsTemplate.sendAndReceive(jmsQueue2, session -> session.createTextMessage(message3 != null ? message3 : message2));
        jmsTemplate.send(new StringBuilder("jmsQueueEvents").toString(), session -> getMessage(message3, session));
        String jmsQueueEvents2;
        if (message2 != null) {
            jmsQueueEvents2 = wrap("jmsQueueEvents3");
        } else {
            jmsQueueEvents2 = super.getJmsQueueEvents2();
        }
        jmsTemplate.send(jmsQueueEvents2, session -> getMessage(message3, session));
        jmsTemplate.send(new PrivateQueueFactory().getQueue(), session -> getMessage(message3, session));
    }

    @Override
    protected String getJmsQueueEvents2() {
        throw new UnsupportedOperationException("getJmsQueueEvents2");
    }

    private TextMessage getMessage(String message3, Session session) throws JMSException {
        return session.createTextMessage(message3);
    }

    public String receive() {
        var message = jmsOperations.receive();
        if (message instanceof TextMessage) try {
            var textMessage = (TextMessage) message;
            return textMessage.getText();
        } catch (JMSException e) {
            throw new RuntimeException(e);
        }
        else if (message == null) {
            throw new RuntimeException("message is null");
        } else {
            throw new UnsupportedOperationException(message.getClass().getName());
        }
    }

    private class PrivateQueueFactory {
        private String getQueue() {
            return "jms-private-queue";
        }
    }
}
