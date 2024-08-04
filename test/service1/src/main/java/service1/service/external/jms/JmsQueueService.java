package service1.service.external.jms;

import lombok.RequiredArgsConstructor;
import org.springframework.jms.core.JmsOperations;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import javax.jms.JMSException;
import javax.jms.TextMessage;

@Service
@RequiredArgsConstructor
public class JmsQueueService {
    private final JmsTemplate jmsTemplate;
    private final JmsOperations jmsOperations;

    public void sendMessage(String message) {
        jmsTemplate.sendAndReceive("jmsQueue", session -> session.createTextMessage(message));
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
