package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 이름 붙인 두 실행기가 STOMP 입 · 출력 채널에 실제로 걸려 있다 — 실행기 빈의 크기만 보는 시험은 {@code WebSocketConfig} 의 채널 연결 줄이 지워져도 통과해,
 * 채널이 스프링 기본 실행기(큐 무제한)로 조용히 돌아간다. 빈이 아니라 <b>채널이 쥔 실행기</b>를 본다.
 */
@SpringBootTest
class WebSocketChannelExecutorWiringTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    private MessageChannel inboundChannel;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private MessageChannel outboundChannel;

    @Autowired
    @Qualifier("inboundTaskExecutor")
    private ThreadPoolTaskExecutor inboundTaskExecutor;

    @Autowired
    @Qualifier("outboundTaskExecutor")
    private ThreadPoolTaskExecutor outboundTaskExecutor;

    @Test
    void 입력_채널은_이름_붙인_수신_실행기를_쓴다() {
        assertThat(((ExecutorSubscribableChannel) inboundChannel).getExecutor()).isSameAs(inboundTaskExecutor);
    }

    @Test
    void 출력_채널은_이름_붙인_팬아웃_실행기를_쓴다() {
        assertThat(((ExecutorSubscribableChannel) outboundChannel).getExecutor()).isSameAs(outboundTaskExecutor);
    }
}
