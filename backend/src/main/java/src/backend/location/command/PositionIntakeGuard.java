package src.backend.location.command;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;

/**
 * 위치 송신의 중복·과다 수신을 가린다(API_SPEC §4.12) — 마지막으로 받아들인 위치보다 {@code recorded_at} 이 새롭지 않거나 그 위치를 받은 뒤
 * {@link #MIN_INTERVAL} 이 지나기 전에 온 위치는 버린다. 단말의 재전송·중복 전달이 이력 행과 방송을 부풀리지 않게 한다.
 *
 * <p>마지막으로 받아들인 위치는 Redis 최신 좌표 키에서 읽는다 — 새 컬럼이나 조회를 만들지 않는다. Redis 가 응답하지 않거나 키가 없으면(만료
 * 포함) 버리지 않고 받아들인다. 버린 위치는 {@value RunPositionStore#DROPPED_METRIC} 에 {@code reason} 별로 센다.
 */
@Component
@RequiredArgsConstructor
public class PositionIntakeGuard {

    private static final String NOT_NEWER = "not_newer";

    private static final String TOO_FREQUENT = "too_frequent";

    /** 마지막으로 받아들인 위치를 받은 뒤 이 시간이 지나야 다음 위치를 받는다 — 송신 주기(2초)의 절반 아래다. */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    private final RunPositionStore runPositionStore;

    private final MeterRegistry meterRegistry;

    /** 이 위치를 버려야 하면 {@code true} — 버린 이유를 세어 두므로 버릴 때만 부른다. */
    public boolean shouldDrop(Long runId, OffsetDateTime recordedAt, OffsetDateTime receivedAt) {
        return runPositionStore.findAcceptedInRedis(runId)
                .map(last -> dropReason(last, recordedAt, receivedAt))
                .map(this::count)
                .orElse(false);
    }

    private static String dropReason(RunPositionRedisValue last, OffsetDateTime recordedAt, OffsetDateTime receivedAt) {
        if (!recordedAt.toInstant().isAfter(last.recordedAt().toInstant())) {
            return NOT_NEWER;
        }
        Duration sinceLast = Duration.between(last.receivedAt(), receivedAt);
        // 음수는 서버끼리의 시계 차이다 — 그 차이만큼 계속 버리지 않도록 받아들인다
        return !sinceLast.isNegative() && sinceLast.compareTo(MIN_INTERVAL) < 0 ? TOO_FREQUENT : null;
    }

    private boolean count(String reason) {
        meterRegistry.counter(RunPositionStore.DROPPED_METRIC, "reason", reason).increment();
        return true;
    }

    /** 버린 적이 없는 기동 직후에도 이름이 0 으로 노출되도록 미리 등록한다. */
    @PostConstruct
    void registerMetrics() {
        for (String reason : new String[] { NOT_NEWER, TOO_FREQUENT }) {
            meterRegistry.counter(RunPositionStore.DROPPED_METRIC, "reason", reason);
        }
    }
}
