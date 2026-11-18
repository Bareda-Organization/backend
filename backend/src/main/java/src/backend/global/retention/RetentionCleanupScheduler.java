package src.backend.global.retention;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.account.repository.VerificationCodeRepository;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.policy.PolicyConstants;
import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.student.repository.LinkCodeRepository;

/**
 * 보존 정리 배치의 진입점(ERD §7 · TECH_DECISIONS §12.2 · Ruling 243) — 보유 기간이 지난
 * 보존 대상 표({@code notification_log} · {@code run_position} · {@code refresh_token} ·
 * {@code link_code} · {@code audit_log})의 행을 회차(배치) 단위로 나눠 지운다(Ruling 324 — {@code link_request} 는 목록 밖이다 · Ruling 445 — {@code audit_log} 가 목록에 든다). 여기에 <b>퇴원 90일이 지난 학생의 개인정보 파기</b>
 * (Ruling 480 ②·520)가 같은 배치 상한·같은 잠금으로 편입돼 있다 — 이쪽은 행을 지우지 않고 익명화한다
 * ({@link StudentAnonymizationService}). Ruling 894 가 두 가지를 더했다 — 지연 알림 발신 기록({@code delay_notice})을
 * {@code notification_log} 와 같은 14일로 지우고, 회차 종료 24시간이 지난 멱등 키를 비운다({@link IdempotencyKeyExpiry} — 승하차 이력 · 연락 기록의
 * 키 값만 비우고 행은 무기한 보존한다).
 * 복구 인증 코드 발급 행({@code verification_code})은 만든 지 하루가 지나면 지운다 — 발급 요청 안의 인스턴스 메모리 타이머가 아니라 이 배치의 분산 락 아래에서 지운다.
 *
 * <p><b>여기 없는 테이블은 지우지 않는 것이 이 클래스의 본체다</b>(ERD §7.1·§7.2 무기한 보존
 * 대상 — {@code rider_status_history} · {@code no_show_case} ·
 * {@code no_show_contact} · {@code exception_report} · {@code emergency_alert} · {@code route_version} ·
 * {@code run_stop}). 새 테이블을 이 배치에 넣으려면 {@link RetentionPolicy} 에 상수를 먼저 더해야
 * 하므로, 무기한 보존 테이블이 실수로 섞여 들어오는 경로 자체가 없다.
 *
 * <p><b>{@code run_position} 은 파티션 DROP, 나머지는 행 단위 DELETE</b>(Ruling 243 → Ruling 670) — 하루
 * 약 27만 행이 쌓이는 위치 이력만 일 단위 파티션이라 만료된 날의 파티션을 통째로 지운다
 * ({@link RunPositionPartitionManager#dropExpired}). 다른 테이블은 한 번의 삭제 호출을
 * {@link RetentionPolicy#BATCH_SIZE} 로 잘라 여러 회차에 나눠 지운다 — 상한 없이 전건을 한 트랜잭션에서 지우면
 * 오래 쌓인 테이블에서 행 잠금을 길게 붙들어 운영 중 조회를 막는다.
 *
 * <p><b>묶음마다 새 트랜잭션이다</b> — {@code RetentionTransactions.call} 이 묶음 하나의 조회({@code findIdsForRetentionCleanup})와 삭제를 한 트랜잭션에서 실행한다(세부는 {@link RetentionTableCleaner}). {@link RetentionTableCleaner#cleanUpSafely} 가 테이블 하나의 예외를
 * 삼켜 다른 테이블의 정리를 막지 않게 한다({@code NoShowEscalationScheduler} 와 같은 축).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    private final NotificationLogRepository notificationLogRepository;

    private final RunPositionPartitionManager runPositionPartitionManager;

    private final RefreshTokenRepository refreshTokenRepository;

    private final LinkCodeRepository linkCodeRepository;

    private final VerificationCodeRepository verificationCodeRepository;

    private final AuditLogRepository auditLogRepository;

    private final DelayNoticeRepository delayNoticeRepository;

    private final IdempotencyKeyExpiry idempotencyKeyExpiry;

    private final RetentionPolicy retentionPolicy;

    private final RetentionTableCleaner tableCleaner;

    private final WithdrawnStudentAnonymizer studentAnonymizer;

    private final Clock clock;

    private final RefreshTokenRowsMetrics refreshTokenRowsMetrics;

    /**
     * 보유 기간이 지난 보존 대상 표의 행을 지우고, 퇴원 90일이 지난 학생의 개인정보를 익명화한다.
     *
     * <p>실행 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 끄기 위함</b>이다 —
     * {@code DailyRunGenerator} 와 같은 형태로, {@code build.gradle} 이 {@code -}(비활성)를 넣는다.
     *
     * <p>심야 권장 창(TECH_DECISIONS §12.1)에 맞춰 {@code DailyRunGenerator}(00:05) 직후인 00:15 를
     * 기본값으로 둔다 — 같은 순간에 겹치지 않게 하려는 것뿐이고, 두 배치 사이에 순서 의존은 없다.
     *
     * <p>잠금 최대 시간(6시간)은 정리가 끝나기 전에 풀려 두 인스턴스가 같은 정리를 돌지 않도록 첫 대량 정리(몇 달 쌓인 행을 5,000행씩 지우는 회차)를 덮는 값이다. 하루 한 번
     * 도는 작업이라 잠금을 쥔 인스턴스가 죽어도 다음 실행(24시간 뒤) 전에 풀린다.
     */
    @Scheduled(cron = "${app.retention.cleanup.cron:0 15 0 * * *}", zone = PolicyConstants.SERVICE_ZONE_NAME)
    @SchedulerLock(name = "retention-cleanup", lockAtMostFor = "PT6H")
    public void cleanUp() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        tableCleaner.cleanUpSafely("notification_log", retentionPolicy.notificationLogCutoff(now), RetentionPolicy.BATCH_SIZE,
                notificationLogRepository::findIdsForRetentionCleanup, notificationLogRepository::deleteAllByIdInBatch);
        // 지연 알림 발신 기록은 학부모에게 간 알림 기록이라 notification_log 와 같은 보존 주기를 읽는다(Ruling 894 ⑦)
        tableCleaner.cleanUpSafely("delay_notice", retentionPolicy.notificationLogCutoff(now), RetentionPolicy.BATCH_SIZE,
                delayNoticeRepository::findIdsForRetentionCleanup, delayNoticeRepository::deleteAllByIdInBatch);
        expireIdempotencyKeysSafely(retentionPolicy.idempotencyKeyCutoff(now));
        dropRunPositionPartitionsSafely(retentionPolicy.runPositionCutoff(now));
        tableCleaner.cleanUpSafely("refresh_token", retentionPolicy.refreshTokenCutoff(now), RetentionPolicy.BATCH_SIZE,
                refreshTokenRepository::findIdsForRetentionCleanup, refreshTokenRepository::deleteAllByIdInBatch);
        recordRefreshTokenRowsSafely();
        tableCleaner.cleanUpSafely("link_code", now, RetentionPolicy.BATCH_SIZE,
                linkCodeRepository::findIdsForRetentionCleanup, linkCodeRepository::deleteAllByIdInBatch);
        tableCleaner.cleanUpSafely("verification_code", retentionPolicy.verificationCodeCutoff(now), RetentionPolicy.BATCH_SIZE,
                verificationCodeRepository::findIdsForRetentionCleanup, verificationCodeRepository::deleteAllByIdInBatch);
        studentAnonymizer.anonymizeSafely(retentionPolicy.withdrawnStudentCutoff(now));
        cleanUpAuditLogs(retentionPolicy.auditLogCutoff(now));
    }

    /** 감사 로그를 category 마다 따로 묶음 삭제한다. */
    private void cleanUpAuditLogs(OffsetDateTime auditCutoff) {
        for (AuditCategory category : AuditCategory.values()) {
            tableCleaner.cleanUpSafely("audit_log/" + category, auditCutoff, RetentionPolicy.BATCH_SIZE,
                    (cutoff, limit) -> auditLogRepository.findIdsForRetentionCleanup(category, cutoff, limit),
                    auditLogRepository::deleteAllByIdInBatch);
        }
    }

    /** 회차 종료 24시간이 지난 멱등 키를 비운다(Ruling 894 ⑥) — 행은 남기고 키만 비운다. 실패는 삼켜 다른 테이블 정리를 막지 않는다. */
    private void expireIdempotencyKeysSafely(OffsetDateTime cutoff) {
        try {
            int cleared = idempotencyKeyExpiry.expire(cutoff, RetentionPolicy.BATCH_SIZE);
            if (cleared > 0) {
                log.info("보존 정리 — client_key {}건 비움", cleared);
            }
        } catch (Exception e) {
            log.warn("보존 정리 실패 — client_key (다음 틱에 재시도)", e);
            tableCleaner.recordItemFailure();
        }
    }

    /**
     * 정리 직후 {@code refresh_token} 남은 행 수를 게이지에 싣는다(Ruling 742) — 운영에는 테이블 크기를 보는 exporter 가 없다. 하루 한 번
     * {@code count(*)} 라 정상 상태(약 310만 행 추정)에서도 부담이 없다. 실패는 삼켜 다른 테이블 정리를 막지 않는다.
     * ponytail: 수천만 행이 되면 {@code pg_class.reltuples} 추정치로 바꾼다.
     */
    private void recordRefreshTokenRowsSafely() {
        try {
            refreshTokenRowsMetrics.update(refreshTokenRepository.countAllRows());
        } catch (Exception e) {
            log.warn("refresh_token 행 수를 세지 못했다 — 게이지는 이전 값 그대로", e);
            tableCleaner.recordItemFailure();
        }
    }

    /**
     * 위치 이력은 행을 지우지 않고 <b>기간이 지난 일 파티션을 통째로 DROP</b> 한다(Ruling 670) — 하루 약 27만 행의
     * 행 단위 DELETE 와 그 뒤 vacuum 이 사라진다. 실패는 {@link RetentionTableCleaner#cleanUpSafely} 와 같이 삼켜 다른 테이블 정리를 막지 않는다.
     */
    private void dropRunPositionPartitionsSafely(OffsetDateTime cutoff) {
        try {
            runPositionPartitionManager.dropExpired(cutoff);
        } catch (Exception e) {
            log.warn("보존 정리 실패 — run_position (다음 틱에 재시도)", e);
            tableCleaner.recordItemFailure();
        }
    }

}
