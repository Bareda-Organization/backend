package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * 세션 하나가 보낼 메시지의 버퍼·시간 한도를 명시로 고정한다(FIX-VERIFY.md §5-1).
 *
 * <p>고치기 전에는 {@code configureWebSocketTransport} 가 한도를 지정하지 않아 Spring 기본값
 * (512KB·10초)을 조용히 상속했다 — 최대 세션(8,112, 09-09 부하 한계 측정 §7) 기준 512KB 는
 * 약 4.1GB 라, 이 시험이 없으면 그 값이 되돌아가도 컴파일도 나머지 시험도 걸리지 않는다.
 */
@SpringBootTest(properties = "app.ws.outbound.send-buffer-size-limit=131072")
class WebSocketOutboundSendLimitTest {

    @Autowired
    @Qualifier("subProtocolWebSocketHandler")
    private WebSocketHandler subProtocolWebSocketHandler;

    @Test
    void 세션_송신_버퍼_한도는_속성으로_정한_만큼이다() {
        SubProtocolWebSocketHandler handler = unwrap();
        assertThat(handler.getSendBufferSizeLimit())
                .as("미설정이면 Spring 기본값 512KB(524288)를 그대로 쓴다")
                .isEqualTo(131072);
    }

    @Test
    void 세션_송신_시간_한도는_기본값_10초로_명시돼_있다() {
        SubProtocolWebSocketHandler handler = unwrap();
        assertThat(handler.getSendTimeLimit())
                .as("미설정이면 Spring 기본값과 값은 같지만 코드에 근거 없이 묻힌다 — 명시로 고정한다")
                .isEqualTo(10_000);
    }

    private SubProtocolWebSocketHandler unwrap() {
        WebSocketHandler actual = WebSocketHandlerDecorator.unwrap(subProtocolWebSocketHandler);
        assertThat(actual).isInstanceOf(SubProtocolWebSocketHandler.class);
        return (SubProtocolWebSocketHandler) actual;
    }
}
