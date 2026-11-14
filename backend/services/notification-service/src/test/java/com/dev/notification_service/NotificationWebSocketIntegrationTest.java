package com.dev.notification_service;

import com.dev.commonlib_kafka.event.TicketsIssuedEvent;
import com.dev.notification_service.client.UserContactClient;
import com.dev.notification_service.event.NotificationEventListener;
import com.dev.notification_service.mail.EmailSender;
import com.dev.notification_service.websocket.NotificationPushService;
import com.dev.notification_service.websocket.WsTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * WebSocket thật trên cổng ngẫu nhiên: handshake bằng ticket một lần, Origin bị giới hạn, thông báo mới
 * được đẩy tới đúng user sau khi ghi DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationWebSocketIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";

    @MockitoBean
    private EmailSender emailSender;

    @MockitoBean
    private UserContactClient userContactClient;

    @LocalServerPort
    private int port;

    @Autowired
    private WsTicketService ticketService;

    @Autowired
    private NotificationPushService pushService;

    @Autowired
    private NotificationEventListener listener;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void connectedUserReceivesOwnNewNotificationOnly() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BlockingQueue<String> buyerMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> otherMessages = new LinkedBlockingQueue<>();
        WebSocketSession buyerSession = connect(ticketService.issue(buyer).ticket(), ORIGIN, buyerMessages);
        WebSocketSession otherSession = connect(ticketService.issue(other).ticket(), ORIGIN, otherMessages);
        await().atMost(5, TimeUnit.SECONDS).until(() -> pushService.sessionCount(buyer) == 1);

        UUID orderId = UUID.randomUUID();
        listener.onTicketsIssued(jsonMapper.writeValueAsString(new TicketsIssuedEvent(
                orderId, buyer, UUID.randomUUID(), 2, new BigDecimal("40.00"), "USD", Instant.now())));

        String message = buyerMessages.poll(5, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        JsonNode json = jsonMapper.readTree(message);
        assertThat(json.path("type").stringValue()).isEqualTo("notification");
        assertThat(json.path("notification").path("type").stringValue()).isEqualTo("TICKETS_ISSUED");
        assertThat(json.path("notification").path("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(otherMessages.poll(500, TimeUnit.MILLISECONDS)).isNull();

        buyerSession.close();
        otherSession.close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> pushService.sessionCount(buyer) == 0);
    }

    @Test
    void ticketWorksOnceAndForeignOriginIsRejected() throws Exception {
        UUID buyer = UUID.randomUUID();
        String ticket = ticketService.issue(buyer).ticket();
        WebSocketSession first = connect(ticket, ORIGIN, new LinkedBlockingQueue<>());

        assertThatThrownBy(() -> connect(ticket, ORIGIN, new LinkedBlockingQueue<>())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> connect("made-up", ORIGIN, new LinkedBlockingQueue<>())).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> connect(ticketService.issue(buyer).ticket(), "https://evil.example",
                new LinkedBlockingQueue<>())).isInstanceOf(Exception.class);
        first.close();
    }

    private WebSocketSession connect(String ticket, String origin, BlockingQueue<String> inbox) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/api/notifications/ws?ticket=" + ticket))
                .get(5, TimeUnit.SECONDS);
    }
}
