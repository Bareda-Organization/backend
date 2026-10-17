package src.backend.global.websocket;

import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 종료 신호를 받으면 열린 WebSocket 세션을 {@code 1012}(서비스 재시작)로 먼저 닫는다(Ruling 900 H). 클라이언트는 {@code 4403} 이외의 닫힘이면 다시 접속하므로 다른 인스턴스에 붙는다.
 * 스프링의 기본 종료는 세션을 {@code 1001}(떠남)로 닫는데, 그 코드로는 "곧 돌아올 서버를 기다리지 말고 다른 곳에 붙으라" 는 뜻이 전해지지 않는다.
 *
 * <p><b>순서</b> — 이 빈의 종료 단계는 스프링의 서브프로토콜 처리기({@code subProtocolWebSocketHandler})와 같고 그 처리기에 의존해 <b>그보다 먼저</b> 멈춘다
 * (의존하는 빈이 먼저 멈춘다). 그 처리기와 웹 서버의 우아한 종료({@code server.shutdown=graceful})보다 앞이라, 진행 중인 HTTP 요청이 끝나기를 기다리는 동안
 * 새 메시지를 받는 세션이 남지 않는다. 종료 동작은 동기다 — 세션을 모두 닫은 뒤에야 돌아온다.
 */
@Component
@DependsOn("subProtocolWebSocketHandler")
@RequiredArgsConstructor
public class WebSocketShutdownClose implements SmartLifecycle {

    private final OpenWebSocketSessions openSessions;

    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        openSessions.closeAllForRestart();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
