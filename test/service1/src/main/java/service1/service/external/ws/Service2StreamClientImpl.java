package service1.service.external.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;

@Service
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(Service2StreamClientImpl.Properties.class)
public class Service2StreamClientImpl implements AutoCloseable {

    private final WebSocketClient webSocketClient;
    private final Properties properties;
    @Value("${service2.url:ws://service2-value-inject}")
    private String service2Url;
    private volatile List<Future<WebSocketSession>> sessionCompletableFuture;

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

    private static URI defaultURI(String str) {
        return URI.create(str);
    }

    private URI currentURI(String str) {
        return URI.create(str);
    }

    @EventListener
    public void onEvent(ApplicationReadyEvent event) {
        sessionCompletableFuture = subscribe();
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
    }

    private void closeAll() {
        for (var webSocketSessionFuture : sessionCompletableFuture) {
            try {
                close(webSocketSessionFuture);
            } catch (Exception e) {
                log.error("close websocket session in shutdown hook", e);
            }
        }
    }

    public List<Future<WebSocketSession>> subscribe() {
        var webSocketHandler = new AbstractWebSocketHandler() {
            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                log.error("service2 subscribe error, session {}", session.getId(), exception);
            }
        };
        var uri = URI.create(service2Url);
        var servUrl = "ws://service3";
        var headers = new WebSocketHttpHeaders();
        Supplier<URI> s = () -> currentURI("ws://currentURI-supplier");
        return List.of(
                webSocketClient.doHandshake(webSocketHandler, "ws://service-template"),
                webSocketClient.doHandshake(webSocketHandler, "ws://service-template/{id}/", "id", "1"),
                webSocketClient.doHandshake(webSocketHandler, headers, URI.create("ws://service2")),
                webSocketClient.doHandshake(webSocketHandler, headers, URI.create(servUrl)),
                webSocketClient.doHandshake(webSocketHandler, headers, uri),
                webSocketClient.doHandshake(webSocketHandler, headers, defaultURI("ws://defaultURI-static")),
                webSocketClient.doHandshake(webSocketHandler, headers, currentURI("ws://currentURI-method")),
                webSocketClient.doHandshake(webSocketHandler, headers, s.get()),
                webSocketClient.doHandshake(webSocketHandler, headers, URI.create(properties.serviceUrl))
        );
    }

    @Override
    public void close() {
        closeAll();
    }

    @ConstructorBinding
    @ConfigurationProperties("service.stream.client")
    public static class Properties {
        private final String serviceUrl;

        public Properties(@DefaultValue("ws://service-property-injected") String serviceUrl) {
            this.serviceUrl = serviceUrl;
        }
    }
}
