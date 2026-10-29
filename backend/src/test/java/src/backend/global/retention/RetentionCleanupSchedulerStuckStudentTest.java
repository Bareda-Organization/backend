package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.account.repository.VerificationCodeRepository;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;

/** 퇴원 학생 파기에서 끝내 파기되지 않는 학생은 한 틱에 한 번만 실패로 센다 · 보존 정리의 잠금 창은 첫 대량 정리를 덮는다. */
class RetentionCleanupSchedulerStuckStudentTest {

    private static final long STUCK_STUDENT = 1L;

    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentAnonymizationService anonymization = mock(StudentAnonymizationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("파기되지 않는 학생이 여러 묶음의 조회에 다시 잡혀도 실패는 한 번만 센다")
    void 같은_학생은_한_틱에_한_번만_센다() {
        given(studentRepository.findIdsForAnonymization(any(), any()))
                .willReturn(ids(STUCK_STUDENT, 200))                     // 1..200
                .willReturn(concat(List.of(STUCK_STUDENT), ids(201, 199))) // 막힌 학생이 다시 조회 앞쪽을 채운다
                .willReturn(List.of(STUCK_STUDENT));
        given(anonymization.anonymize(any())).willAnswer(call -> {
            List<Long> batch = call.getArgument(0);
            return batch.size() - (batch.contains(STUCK_STUDENT) ? 1 : 0);
        });

        scheduler().cleanUp();

        assertThat(registry.counter("schoolbus.scheduler.failures", "scheduler", "retention-cleanup").count())
                .as("파기되지 않는 학생 1명").isEqualTo(1.0);
    }

    @Test
    @DisplayName("새 학생이 없고 막힌 학생만 남으면 같은 틱에서 되풀이하지 않는다")
    void 막힌_학생만_남으면_멈춘다() {
        // 같은 200명이 계속 돌아오는 상황 — 되풀이를 막지 못해도 시험이 끝나도록 세 번 뒤에는 빈 목록을 준다
        given(studentRepository.findIdsForAnonymization(any(), any())).willReturn(ids(STUCK_STUDENT, 200),
                ids(STUCK_STUDENT, 200), ids(STUCK_STUDENT, 200), List.of());
        given(anonymization.anonymize(any())).willReturn(199);

        scheduler().cleanUp();

        org.mockito.Mockito.verify(studentRepository, org.mockito.Mockito.times(2)).findIdsForAnonymization(any(), any());
    }

    @Test
    @DisplayName("보존 정리의 잠금 최대 시간은 첫 대량 정리를 덮도록 한 시간 이상이고 다음 실행(하루) 전에 풀린다")
    void 잠금_창이_대량_정리를_덮는다() throws Exception {
        SchedulerLock lock = RetentionCleanupScheduler.class.getMethod("cleanUp").getAnnotation(SchedulerLock.class);

        Duration atMost = Duration.parse(lock.lockAtMostFor());

        assertThat(atMost).isGreaterThanOrEqualTo(Duration.ofHours(1)).isLessThan(Duration.ofHours(24));
    }

    private RetentionCleanupScheduler scheduler() {
        NotificationLogRepository notificationLogs = mock(NotificationLogRepository.class);
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        LinkCodeRepository linkCodes = mock(LinkCodeRepository.class);
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        given(notificationLogs.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(refreshTokens.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(linkCodes.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(auditLogs.findIdsForRetentionCleanup(any(), any(), any())).willReturn(List.of());
        return new RetentionCleanupScheduler(notificationLogs, mock(RunPositionPartitionManager.class), refreshTokens, linkCodes, mock(VerificationCodeRepository.class), auditLogs, mock(DelayNoticeRepository.class), mock(IdempotencyKeyExpiry.class), new RetentionPolicy(),
                new RetentionTableCleaner(new RetentionTransactions(mock(org.springframework.transaction.PlatformTransactionManager.class),
                        mock(org.springframework.jdbc.core.JdbcTemplate.class)), registry),
                new WithdrawnStudentAnonymizer(studentRepository, anonymization, new RetentionTableCleaner(new RetentionTransactions(mock(org.springframework.transaction.PlatformTransactionManager.class),
                        mock(org.springframework.jdbc.core.JdbcTemplate.class)), registry)),
                Clock.fixed(Instant.parse("2032-06-15T00:00:00Z"), ZoneId.of("Asia/Seoul")), new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));
    }

    private static List<Long> ids(long first, int count) {
        List<Long> ids = new ArrayList<>(count);
        for (long i = first; i < first + count; i++) {
            ids.add(i);
        }
        return ids;
    }

    private static List<Long> concat(List<Long> head, List<Long> tail) {
        List<Long> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }
}
