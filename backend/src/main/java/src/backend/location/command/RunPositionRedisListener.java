package src.backend.location.command;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.event.RunPositionReceivedEvent;

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
 * <p>⚠ <b>와이어 포맷 계약 — 평문 camelCase JSON.</b> {@link RunPositionRedisValue} 를
 * {@link StringRedisTemplate} 으로 쓴다({@code @class} 타입 태그를 심는
 * {@code RedisTemplate<String,Object>} 가 아니다) — 자세한 이유·사고 이력은
 * {@link RunPositionRedisValue} 자바독을 본다. 이 클래스가 쓰는 값을 T3
 * ({@code src.backend.location.proximity.RunPositionReader})·T4
 * ({@code src.backend.student.query.RunPositionCache})가 각자 평문으로 파싱하므로, 여기서 다시 다형
 * 직렬화기로 되돌리면 그 둘이 동시에 깨진다(Phase 10 게이트 리뷰 R1 Critical).
 */
@Component
@RequiredArgsConstructor
public class RunPositionRedisListener {

    private static final Logger log = LoggerFactory.getLogger(RunPositionRedisListener.class);

    /** 운행 종료 후 자연 소멸 — 이력은 {@code run_position} 이 별도로 갖는다(공유 계약, 목표 3). */
    private static final Duration TTL = Duration.ofMinutes(30);

    /**
     * 전역 {@code ObjectMapper} 를 쓰지 않는다 — {@code application.yml} 의 {@code SNAKE_CASE} 네이밍
     * 전략이 걸려 있어(T4 {@code RunPositionCache} 자바독과 같은 이유) {@code recordedAt} 이
     * {@code recorded_at} 으로 나가 T3·T4 계약(리터럴 camelCase)이 깨진다. 이 클래스 전용 인스턴스로
     * 기본 네이밍(camelCase)을 그대로 쓴다.
     */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate stringRedisTemplate;

    /** 커밋된 위치 수신 이벤트로 Redis 최신 좌표 키를 덮어쓴다 — 실패는 삼키고 다음 송신에 맡긴다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRedisAfterCommit(RunPositionReceivedEvent event) {
        try {
            // 현재 정차지 이름은 위치를 저장한 트랜잭션이 한 번 계산해 이벤트에 실어 보낸다(BR-100) — 여기서 다시
            // 회차·정차 목록을 읽지 않는다.
            RunPositionRedisValue value = new RunPositionRedisValue(event.lat(), event.lng(), event.recordedAt(),
                    event.receivedAt(), event.currentStopName());
            String json = JSON_MAPPER.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(keyOf(event.runId()), json, TTL);
        } catch (RuntimeException e) {
            log.warn("[location] Redis 최신 좌표 갱신이 실패해 다음 송신으로 넘긴다. runId={}", event.runId(), e);
        }
    }

    private String keyOf(Long runId) {
        return "run:" + runId + ":position";
    }
}
