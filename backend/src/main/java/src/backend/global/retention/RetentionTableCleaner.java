package src.backend.global.retention;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보존 정리 배치가 테이블 하나를 지울 때 쓰는 공통 절차 — 배치 상한만큼씩 조회·삭제를 반복하고(묶음마다 새 트랜잭션), 실패는 삼켜 다른 테이블 정리를 막지 않으며, 삼킨 실패를
 * 지표({@code schoolbus.scheduler.failures})로 센다. {@link RetentionCleanupScheduler} 와 {@link WithdrawnStudentAnonymizer} 가 같은 실패 지표를 올리도록 이 클래스가 센다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionTableCleaner {

    private final RetentionTransactions retentionTransactions;

    private final MeterRegistry meterRegistry;

    /**
     * 테이블 하나를 정리하고, 무엇이 됐든 실패를 삼켜 다른 테이블의 정리를 막지 않는다.
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그가 이 메서드를 끊어 {@code RetentionCleanupScheduler#cleanUp} 의 나머지 4개 테이블 정리 여부와
     * 무관하게 배치 자체가 실패로 끝난다({@code NoShowEscalationScheduler.escalateSafely} 와 같은 근거).
     */
    public void cleanUpSafely(String tableName, OffsetDateTime cutoff, int batchSize,
            BiFunction<OffsetDateTime, Limit, List<Long>> finder, Consumer<List<Long>> deleter) {
        try {
            cleanUpTable(tableName, cutoff, batchSize, finder, deleter);
        } catch (Exception e) {
            log.warn("보존 정리 실패 — {} (다음 틱에 재시도)", tableName, e);
            recordItemFailure();
        }
    }

    /**
     * 배치 상한만큼씩 반복해 지운다 — 한 회차가 상한만큼 지웠으면(= 아직 남았을 수 있으면)
     * 같은 틱 안에서 다음 회차로 이어가고, 상한보다 적게 지웠으면(= 다 지웠으면) 멈춘다.
     */
    private void cleanUpTable(String tableName, OffsetDateTime cutoff, int batchSize,
            BiFunction<OffsetDateTime, Limit, List<Long>> finder, Consumer<List<Long>> deleter) {
        int totalDeleted = 0;
        int roundDeleted;
        do {
            // 한 묶음의 조회와 삭제를 문장 시간 상한이 풀린 트랜잭션 하나로 돌린다(DA-4) — 묶음마다 새 트랜잭션이라 잠금은 길게 쥐지 않는다
            roundDeleted = retentionTransactions.call(() -> {
                List<Long> ids = finder.apply(cutoff, Limit.of(batchSize));
                if (!ids.isEmpty()) {
                    deleter.accept(ids);
                }
                return ids.size();
            });
            totalDeleted += roundDeleted;
        } while (roundDeleted == batchSize);

        if (totalDeleted > 0) {
            log.info("보존 정리 — {} {}행 처리", tableName, totalDeleted);
        }
    }

    /** 잡아 삼킨 건별 실패 1건(BR-064) — 주기 전체의 예외는 스프링 기본 계측({@code tasks.scheduled.execution} {@code outcome=ERROR})이 센다. */
    public void recordItemFailure() {
        meterRegistry.counter("schoolbus.scheduler.failures", "scheduler", "retention-cleanup").increment();
    }
}
