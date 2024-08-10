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

    public final void sendMessage(String message, String message2, String message3) {
        jmsTemplate.sendAndReceive(wrap("jmsQueue"), session -> session.createTextMessage(message3));
        jmsTemplate.send(new StringBuilder("jmsQueueEvents").toString(), session -> getMessage(message3, session));
        jmsTemplate.send(super.getJmsQueueEvents2(), session -> getMessage(message3, session));
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
}
