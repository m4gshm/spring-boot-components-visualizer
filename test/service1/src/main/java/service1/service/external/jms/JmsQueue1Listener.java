package service1.service.external.jms;

import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.annotation.JmsListeners;
import org.springframework.stereotype.Service;
import service1.service.external.CoreService;

@Service
@RequiredArgsConstructor
public class JmsQueue1Listener {

    private final CoreService coreService;

    @JmsListener(destination = "queue1")
    public void listenQueue1(String message) {

    }

    @JmsListeners(@JmsListener(destination = "queue2"))
    public void listenQueue2(String message) {

    }
}
