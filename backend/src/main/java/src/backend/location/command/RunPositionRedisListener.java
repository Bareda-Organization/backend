package src.backend.location.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunPositionStore;

/**
 * {@link RunPositionReceivedEvent} 커밋 후 Redis 최신 좌표를 갱신한다(목표 3, 조율자 판단) — 이력
 * ({@code run_position})이 먼저 적재된 뒤에만 도는 이유는
 * {@link RunPositionCommandService} 자바독을 본다.
 *
 * <p>예외를 삼키는 이유는 알림 모듈의 {@code NotificationDispatchListener} 와 같다 — 그쪽 패키지를
 * {@code @link} 로 가리키지 않는 것은 {@code NotificationModuleIsolationTest} 가 프로덕션 소스의
 * 알림 패키지 문자열을 전부 위반으로 세기 때문이다(주석도 센다). 이름만 적어도 가리키는 대상은
 * 같다 — 이미 커밋된 위치 수신을 여기서 실패로 뒤집을 수 없고, 이 갱신이 실패해도 2초(Ruling 279) 뒤 다음
 * 송신이 같은 키를 덮어써 스스로 회복된다(조율자 판단, 목표 3).
 *
 * <p>키·값 형식(평문 camelCase JSON)은 {@link RunPositionStore} 한 곳이 정한다(BR-098) — 읽는 쪽도 같은
 * 클래스라 한쪽만 형식이 바뀌는 사고({@link RunPositionRedisValue} 자바독)가 구조적으로 막힌다.
 */
@Component
@RequiredArgsConstructor
public class RunPositionRedisListener {

    private static final Logger log = LoggerFactory.getLogger(RunPositionRedisListener.class);

    private final RunPositionStore runPositionStore;

    /** 커밋된 위치 수신 이벤트로 Redis 최신 좌표 키를 덮어쓴다 — 실패는 삼키고 다음 송신에 맡긴다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisAfterCommit(RunPositionReceivedEvent event) {
        try {
            // 현재 정차지 이름은 위치를 저장한 트랜잭션이 한 번 계산해 이벤트에 실어 보낸다(BR-100) — 여기서 다시
            // 회차·정차 목록을 읽지 않는다.
            RunPositionRedisValue value = new RunPositionRedisValue(event.lat(), event.lng(), event.recordedAt(),
                    event.receivedAt(), event.currentStopName());
            runPositionStore.save(event.runId(), value);
        } catch (RuntimeException e) {
            log.warn("[location] Redis 최신 좌표 갱신이 실패해 다음 송신으로 넘긴다. runId={}", event.runId(), e);
        }
    }
}
