package src.backend.global.retention;

import static org.mockito.Mockito.mock;

import java.time.Clock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.account.repository.VerificationCodeRepository;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.student.repository.LinkCodeRepository;

/** 보존 정리 스케줄러를 시험에서 조립하는 한 곳 — 시험이 따로 정하지 않는 의존(복구 코드 · 지연 알림 · 멱등 키 · 지표)은 빈 대역으로 채운다. */
final class RetentionSchedulerFactory {

    private RetentionSchedulerFactory() {
    }

    /** 트랜잭션 관리자 · JDBC 를 대역으로 둔 {@link RetentionTransactions} — 정리 묶음이 실제 트랜잭션 없이 그대로 지나간다(문장 시간 상한은 DataAccessSettingsTest 가 본다). */
    static RetentionTransactions passThroughTransactions() {
        return new RetentionTransactions(mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class));
    }

    static RetentionCleanupScheduler of(NotificationLogRepository notificationLogs,
            RunPositionPartitionManager partitions, RefreshTokenRepository refreshTokens, LinkCodeRepository linkCodes,
            AuditLogRepository auditLogs, RetentionPolicy policy, RetentionTableCleaner tableCleaner,
            WithdrawnStudentAnonymizer anonymizer, Clock clock) {
        return new RetentionCleanupScheduler(notificationLogs, partitions, refreshTokens, linkCodes,
                mock(VerificationCodeRepository.class), auditLogs, mock(DelayNoticeRepository.class),
                mock(IdempotencyKeyExpiry.class), policy, tableCleaner, anonymizer, clock,
                new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));
    }
}
