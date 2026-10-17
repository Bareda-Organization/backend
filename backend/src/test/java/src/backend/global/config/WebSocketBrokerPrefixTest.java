package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;

/**
 * 브로커 접두사는 {@code /topic} 하나다(R59 S-10, Ruling 900 G) — 서버가 {@code /queue} 로 보내는 곳도, {@code /user} 로 개별 전달하는 곳도 없다. 접두사가 남아 있으면
 * 클라이언트가 그 목적지를 구독 · 발행하려는 시도가 브로커까지 닿을 길이 열린 채로 남는다. 클라이언트의 {@code SEND} 거부는 {@code StompChannelAuthorizationTest} 가 본다.
 */
@SpringBootTest
class WebSocketBrokerPrefixTest {

    @Autowired
    @Qualifier("simpleBrokerMessageHandler")
    private SimpleBrokerMessageHandler broker;

    @Test
    void 브로커_접두사는_topic_하나뿐이다() {
        assertThat(broker.getDestinationPrefixes()).containsExactly("/topic");
    }
}
