package src.backend.global.retention;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 보존 정리 배치의 진입점(목표 6·7, ERD §7 · TECH_DECISIONS §12.2 · Ruling 243) — 보유 기간이 지난
 * 5개 테이블({@code notification_log} · {@code run_position} · {@code refresh_token} ·
 * {@code link_code} · {@code audit_log})의 행을 회차(배치) 단위로 나눠 지운다(Ruling 324 — {@code link_request}
 * 삭제로 5개→4개, Ruling 445 — {@code audit_log} 편입으로 4개→5개). 여기에 <b>퇴원 90일이 지난 학생의 개인정보 파기</b>
 * (Ruling 480 ②·520)가 같은 배치 상한·같은 잠금으로 편입돼 있다 — 이쪽은 행을 지우지 않고 익명화한다
 * ({@link StudentAnonymizationService}).
 *
 * <p><b>여기 없는 테이블은 지우지 않는 것이 이 클래스의 본체다</b>(목표 6, ERD §7.1·§7.2 무기한 보존
 * 대상 — {@code rider_status_history} · {@code no_show_case} ·
 * {@code no_show_contact} · {@code exception_report} · {@code emergency_alert} · {@code route_version} ·
 * {@code run_stop}). 새 테이블을 이 배치에 넣으려면 {@link RetentionPolicy} 에 상수를 먼저 더해야
 * 하므로, 무기한 보존 테이블이 실수로 섞여 들어오는 경로 자체가 없다.
 *
 * <p><b>{@code run_position} 은 파티션 DROP, 나머지는 행 단위 DELETE</b>(Ruling 243 → R46-LATERBE Ruling 670) — 하루
 * 약 27만 행이 쌓이는 위치 이력만 일 단위 파티션이라 만료된 날의 파티션을 통째로 지운다
 * ({@link RunPositionPartitionManager#dropExpired}). 다른 테이블은 한 번의 삭제 호출을
 * {@link RetentionPolicy#BATCH_SIZE} 로 잘라 여러 회차에 나눠 지운다(목표 7) — 상한 없이 전건을 한 트랜잭션에서 지우면
 * 오래 쌓인 테이블에서 행 잠금을 길게 붙들어 운영 중 조회를 막는다.
 *
 * <p><b>테이블마다 개별 트랜잭션이다</b> — 조회({@code findIdsForRetentionCleanup})와 삭제
 * ({@code deleteAllByIdInBatch}, {@link org.springframework.data.jpa.repository.JpaRepository} 상속
 * 메서드)는 각각 별도의 리포지토리 메서드 호출이라, {@code SimpleJpaRepository} 의 메서드별
 * {@code @Transactional} 프록시가 호출마다 독립된 트랜잭션을 연다 — 이 클래스에 별도의
 * {@code REQUIRES_NEW} 헬퍼를 두지 않아도 된다. {@link #cleanUpSafely} 가 테이블 하나의 예외를
 * 삼켜 다른 테이블의 정리를 막지 않게 한다({@code NoShowEscalationScheduler} 와 같은 축).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    /**
     * 퇴원 학생 파기의 묶음 크기(R46 T-8) — 삭제용 {@link RetentionPolicy#BATCH_SIZE}(5,000)는 한 문장짜리 벌크 DELETE 기준이다.
     * 파기는 한 트랜잭션이 학생·계정 엔티티를 전부 적재하고 커밋 때 건별 UPDATE 를 쏟아내 묶음이 크면 연결을 오래 쥔다.
     */
    private static final int ANONYMIZATION_BATCH_SIZE = 200;

    private final NotificationLogRepository notificationLogRepository;

    private final RunPositionPartitionManager runPositionPartitionManager;

    private final RefreshTokenRepository refreshTokenRepository;

    private final LinkCodeRepository linkCodeRepository;

    private final AuditLogRepository auditLogRepository;

    private final StudentRepository studentRepository;

    private final StudentAnonymizationService studentAnonymizationService;

    private final RetentionPolicy retentionPolicy;

    private final Clock clock;

    private final SchedulerHealthMetrics schedulerHealthMetrics;

    private final RefreshTokenRowsMetrics refreshTokenRowsMetrics;

    /**
     * 보유 기간이 지난 5개 테이블의 행을 지우고, 퇴원 90일이 지난 학생의 개인정보를 익명화한다.
     *
     * <p>실행 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 끄기 위함</b>이다 —
     * {@code DailyRunGenerator} 와 같은 형태로, {@code build.gradle} 이 {@code -}(비활성)를 넣는다.
     *
     * <p>심야 권장 창(TECH_DECISIONS §12.1)에 맞춰 {@code DailyRunGenerator}(00:05) 직후인 00:15 를
     * 기본값으로 둔다 — 같은 순간에 겹치지 않게 하려는 것뿐이고, 두 배치 사이에 순서 의존은 없다.
     */
    @Scheduled(cron = "${app.retention.cleanup.cron:0 15 0 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "retention-cleanup", lockAtMostFor = "PT10M")
    public void cleanUp() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        cleanUpSafely("notification_log", retentionPolicy.notificationLogCutoff(now), RetentionPolicy.BATCH_SIZE,
                notificationLogRepository::findIdsForRetentionCleanup, notificationLogRepository::deleteAllByIdInBatch);
        dropRunPositionPartitionsSafely(retentionPolicy.runPositionCutoff(now));
        cleanUpSafely("refresh_token", retentionPolicy.refreshTokenCutoff(now), RetentionPolicy.BATCH_SIZE,
                refreshTokenRepository::findIdsForRetentionCleanup, refreshTokenRepository::deleteAllByIdInBatch);
        recordRefreshTokenRowsSafely();
        cleanUpSafely("link_code", now, RetentionPolicy.BATCH_SIZE,
                linkCodeRepository::findIdsForRetentionCleanup, linkCodeRepository::deleteAllByIdInBatch);
        cleanUpSafely("student_anonymization", retentionPolicy.withdrawnStudentCutoff(now), ANONYMIZATION_BATCH_SIZE,
                studentRepository::findIdsForAnonymization, studentAnonymizationService::anonymize);
        for (AuditCategory category : AuditCategory.values()) {
            cleanUpSafely("audit_log/" + category, retentionPolicy.auditLogCutoff(now), RetentionPolicy.BATCH_SIZE,
                    (cutoff, limit) -> auditLogRepository.findIdsForRetentionCleanup(category, cutoff, limit),
                    auditLogRepository::deleteAllByIdInBatch);
        }
    }

    /**
     * 정리 직후 {@code refresh_token} 남은 행 수를 게이지에 싣는다(R47, Ruling 742) — 운영에는 테이블 크기를 보는 exporter 가 없다. 하루 한 번
     * {@code count(*)} 라 정상 상태(약 310만 행 추정)에서도 부담이 없다. 실패는 삼켜 다른 테이블 정리를 막지 않는다.
     * ponytail: 수천만 행이 되면 {@code pg_class.reltuples} 추정치로 바꾼다.
     */
    private void recordRefreshTokenRowsSafely() {
        try {
            refreshTokenRowsMetrics.update(refreshTokenRepository.count());
        } catch (Exception e) {
            log.warn("refresh_token 행 수를 세지 못했다 — 게이지는 이전 값 그대로", e);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
    }

    /**
     * 위치 이력은 행을 지우지 않고 <b>기간이 지난 일 파티션을 통째로 DROP</b> 한다(R46-LATERBE B-1, Ruling 670) — 하루 약 27만 행의
     * 행 단위 DELETE 와 그 뒤 vacuum 이 사라진다. 실패는 {@link #cleanUpSafely} 와 같이 삼켜 다른 테이블 정리를 막지 않는다.
     */
    private void dropRunPositionPartitionsSafely(OffsetDateTime cutoff) {
        try {
            runPositionPartitionManager.dropExpired(cutoff);
        } catch (Exception e) {
            log.warn("보존 정리 실패 — run_position (다음 틱에 재시도)", e);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
    }

    /**
     * 테이블 하나를 정리하고, 무엇이 됐든 실패를 삼켜 다른 테이블의 정리를 막지 않는다.
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그가 이 메서드를 끊어 {@link #cleanUp} 의 나머지 4개 테이블 정리 여부와
     * 무관하게 배치 자체가 실패로 끝난다({@code NoShowEscalationScheduler.escalateSafely} 와 같은 근거).
     */
    private void cleanUpSafely(String tableName, OffsetDateTime cutoff, int batchSize,
            BiFunction<OffsetDateTime, Limit, List<Long>> finder, Consumer<List<Long>> deleter) {
        try {
            cleanUpTable(tableName, cutoff, batchSize, finder, deleter);
        } catch (Exception e) {
            log.warn("보존 정리 실패 — {} (다음 틱에 재시도)", tableName, e);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
    }

    /**
     * 배치 상한(목표 7)만큼씩 반복해 지운다 — 한 회차가 상한만큼 지웠으면(= 아직 남았을 수 있으면)
     * 같은 틱 안에서 다음 회차로 이어가고, 상한보다 적게 지웠으면(= 다 지웠으면) 멈춘다.
     */
    private void cleanUpTable(String tableName, OffsetDateTime cutoff, int batchSize,
            BiFunction<OffsetDateTime, Limit, List<Long>> finder, Consumer<List<Long>> deleter) {
        int totalDeleted = 0;
        int roundDeleted;
        do {
            List<Long> ids = finder.apply(cutoff, Limit.of(batchSize));
            roundDeleted = ids.size();
            if (roundDeleted > 0) {
                deleter.accept(ids);
                totalDeleted += roundDeleted;
            }
        } while (roundDeleted == batchSize);

        if (totalDeleted > 0) {
            log.info("보존 정리 — {} {}행 처리", tableName, totalDeleted);
        }
    }
}
