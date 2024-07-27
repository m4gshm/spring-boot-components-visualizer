package service1.service.external.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

@Service
@Slf4j
@RequiredArgsConstructor
public class Service2StreamClientImpl implements AutoCloseable {

    private final WebSocketClient webSocketClient;
    private volatile Future<WebSocketSession> sessionCompletableFuture;

    private static void close(Future<WebSocketSession> subscribed) {
        if (subscribed == null) {
            return;
        }
        final WebSocketSession session;
        try {
            session = subscribed.get(5, SECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        var open = session.isOpen();
        if (!open) try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @EventListener
    public void onEvent(ApplicationReadyEvent event) {
        sessionCompletableFuture = subscribe();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close(sessionCompletableFuture);
            } catch (Exception e) {
                log.error("close websocket session in shutdown hook", e);
            }
        }));
    }

    public Future<WebSocketSession> subscribe() {
        return webSocketClient.doHandshake(new AbstractWebSocketHandler() {
            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                log.error("service2 subscribe error, session {}", session.getId(), exception);
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://service2"));
    }

    @Override
    public void close() {
        close(this.sessionCompletableFuture);
    }
}
