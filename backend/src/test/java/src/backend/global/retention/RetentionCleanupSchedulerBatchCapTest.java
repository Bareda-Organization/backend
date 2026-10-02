package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.location.infrastructure.RunPositionPartitionManager;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link RetentionCleanupScheduler#cleanUp} 이 <b>조회를 부를 때마다</b> 배치 상한을 실제로 넘기는지
 * 순수 단위 시험(Mockito, Spring 컨텍스트 없음)으로 확인한다(목표 7).
 *
 * <p>{@code RetentionCleanupSchedulerTest} 의 배치 상한 시험은 리포지토리 메서드를 <b>직접</b>
 * {@code Limit.of(BATCH_SIZE)} 를 넘겨 호출해, "그 값을 주면 그 값만큼만 돌아오는가"(JPQL 이 {@code
 * Limit} 파라미터를 실제로 반영하는가)만 검사한다. <b>스케줄러가 그 값을 실제로 넘기는지는 별개
 * 질문</b>이다 — {@link RetentionCleanupScheduler} 안에서 {@code Limit.of(RetentionPolicy.BATCH_SIZE)}
 * 를 {@code Limit.unlimited()} 로 바꿔도(상한 자체를 없애는 결함) 그 시험은 여전히 통과한다. 실제로
 * 심어서 확인했다 — 그 결함을 심었을 때 기존 시험 6개가 전부 초록이었다.
 *
 * <p>그래서 이 시험은 <b>스케줄러가 조회 메서드에 실제로 넘기는 {@link Limit} 인자를 캡처</b>해
 * {@code BATCH_SIZE} 와 정확히 같은지 확인한다 — 스케줄러 내부에서 상한 상수가 빠지거나 다른 값으로
 * 바뀌면 이 시험만 실패한다.
 */
class RetentionCleanupSchedulerBatchCapTest {

    private final Clock clock = Clock.fixed(Instant.parse("2032-06-15T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final RetentionPolicy retentionPolicy = new RetentionPolicy();

    private final NotificationLogRepository notificationLogRepository = mock(NotificationLogRepository.class);
    private final RunPositionPartitionManager runPositionPartitionManager = mock(RunPositionPartitionManager.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final LinkCodeRepository linkCodeRepository = mock(LinkCodeRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final StudentRepository studentRepository = mock(StudentRepository.class);
    private final StudentAnonymizationService studentAnonymizationService = mock(StudentAnonymizationService.class);

    @Test
    void 매_조회_호출마다_배치_상한을_그대로_넘긴다() {
        // 다른 4개 테이블은 빈 목록만 반환해 이 시험이 notification_log 호출에만 집중하게 한다.
        given(refreshTokenRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(linkCodeRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(auditLogRepository.findIdsForRetentionCleanup(any(), any(), any())).willReturn(List.of());

        // notification_log 는 2회차에 걸쳐 지워지도록 1회차엔 상한만큼, 2회차엔 그보다 적게 돌려준다 —
        // 그래야 "매 호출마다" 상한이 유지되는지(1회차만 우연히 맞고 2회차부터 새는 결함도) 잡힌다.
        List<Long> firstRound = fakeIds(RetentionPolicy.BATCH_SIZE);
        List<Long> secondRound = fakeIds(3);
        given(notificationLogRepository.findIdsForRetentionCleanup(any(), any()))
                .willReturn(firstRound)
                .willReturn(secondRound);

        RetentionCleanupScheduler scheduler = new RetentionCleanupScheduler(
                notificationLogRepository, runPositionPartitionManager, refreshTokenRepository,
                linkCodeRepository, auditLogRepository, studentRepository, studentAnonymizationService,
                retentionPolicy, clock,
                new SchedulerHealthMetrics(
                        new SimpleMeterRegistry()),
                new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));

        scheduler.cleanUp();

        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        org.mockito.Mockito.verify(notificationLogRepository, org.mockito.Mockito.times(2))
                .findIdsForRetentionCleanup(any(OffsetDateTime.class), limitCaptor.capture());

        assertThat(limitCaptor.getAllValues())
                .as("조회 2회 전부 배치 상한(%s)을 그대로 넘겨야 한다 — 상한이 빠지면(예: Limit.unlimited()) "
                        + "1회차에 전건을 긁어와 다회차 배치가 성립하지 않는다", RetentionPolicy.BATCH_SIZE)
                .allSatisfy(limit -> assertThat(limit).isEqualTo(Limit.of(RetentionPolicy.BATCH_SIZE)));
    }

    /**
     * R46 T-8 — 퇴원 학생 파기는 5,000명이 아니라 <b>200명씩</b> 끊어 처리한다. 한 트랜잭션이 학생·계정 엔티티를 전부 적재하고
     * 커밋 때 건별 UPDATE 를 쏟아내므로 묶음이 크면 연결을 오래 쥔다(삭제용 상수 5,000 은 한 문장짜리 벌크 DELETE 기준이다).
     */
    @Test
    void 학생_파기는_200명씩_끊어_처리한다() {
        given(notificationLogRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(refreshTokenRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(linkCodeRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(auditLogRepository.findIdsForRetentionCleanup(any(), any(), any())).willReturn(List.of());
        given(studentRepository.findIdsForAnonymization(any(), any()))
                .willReturn(fakeIds(200))
                .willReturn(fakeIds(50));
        given(studentAnonymizationService.anonymize(any()))
                .willAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        RetentionCleanupScheduler scheduler = new RetentionCleanupScheduler(
                notificationLogRepository, runPositionPartitionManager, refreshTokenRepository,
                linkCodeRepository, auditLogRepository, studentRepository, studentAnonymizationService,
                retentionPolicy, clock, new SchedulerHealthMetrics(new SimpleMeterRegistry()),
                new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));

        scheduler.cleanUp();

        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        org.mockito.Mockito.verify(studentRepository, org.mockito.Mockito.times(2))
                .findIdsForAnonymization(any(OffsetDateTime.class), limitCaptor.capture());
        assertThat(limitCaptor.getAllValues()).as("조회 2회 전부 파기 묶음 200").allSatisfy(
                limit -> assertThat(limit).isEqualTo(Limit.of(200)));
        org.mockito.Mockito.verify(studentAnonymizationService, org.mockito.Mockito.times(2)).anonymize(any());
    }

    /**
     * BR-311 — 파기 수가 조회 수와 같다는 보장이 없다(사진 파일을 못 지운 학생 · 처리에 실패한 학생은 남는다). 한 묶음에서 하나도 파기하지
     * 못했는데 상한만 보고 다시 조회하면 같은 200명을 끝없이 되풀이하므로, 그 회차에서 멈추고 건너뛴 학생 수만큼 실패 지표를 올린다.
     */
    @Test
    void 한_묶음에서_하나도_파기하지_못하면_같은_묶음을_되풀이하지_않고_건너뛴_수만큼_실패를_센다() {
        given(notificationLogRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(refreshTokenRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(linkCodeRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(auditLogRepository.findIdsForRetentionCleanup(any(), any(), any())).willReturn(List.of());
        given(studentRepository.findIdsForAnonymization(any(), any())).willReturn(fakeIds(200));
        given(studentAnonymizationService.anonymize(any())).willReturn(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionCleanupScheduler scheduler = new RetentionCleanupScheduler(
                notificationLogRepository, runPositionPartitionManager, refreshTokenRepository,
                linkCodeRepository, auditLogRepository, studentRepository, studentAnonymizationService,
                retentionPolicy, clock, new SchedulerHealthMetrics(registry),
                new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));

        scheduler.cleanUp();

        org.mockito.Mockito.verify(studentRepository, org.mockito.Mockito.times(1))
                .findIdsForAnonymization(any(OffsetDateTime.class), any(Limit.class));
        assertThat(registry.counter("schoolbus.scheduler.failures", "scheduler", "retention-cleanup").count())
                .as("파기하지 못한 학생 200명").isEqualTo(200.0);
    }

    /**
     * R46-LATERBE B-1 — 위치 이력은 행이 아니라 파티션 단위로 지운다. 컷오프는 90일 전이고, 파티션 DROP 이 예외를 던져도 다른
     * 테이블 정리는 이어진다(알림 로그 정리 호출이 실제로 일어난다).
     */
    @Test
    void 위치_이력은_90일_전_컷오프로_파티션_DROP_을_부르고_실패해도_다른_정리는_이어진다() {
        given(notificationLogRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(refreshTokenRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(linkCodeRepository.findIdsForRetentionCleanup(any(), any())).willReturn(List.of());
        given(auditLogRepository.findIdsForRetentionCleanup(any(), any(), any())).willReturn(List.of());
        given(studentRepository.findIdsForAnonymization(any(), any())).willReturn(List.of());
        given(runPositionPartitionManager.dropExpired(any())).willThrow(new IllegalStateException("lock timeout"));
        RetentionCleanupScheduler scheduler = new RetentionCleanupScheduler(
                notificationLogRepository, runPositionPartitionManager, refreshTokenRepository,
                linkCodeRepository, auditLogRepository, studentRepository, studentAnonymizationService,
                retentionPolicy, clock, new SchedulerHealthMetrics(new SimpleMeterRegistry()),
                new RefreshTokenRowsMetrics(new SimpleMeterRegistry()));

        scheduler.cleanUp();

        org.mockito.Mockito.verify(runPositionPartitionManager)
                .dropExpired(retentionPolicy.runPositionCutoff(OffsetDateTime.now(clock)));
        org.mockito.Mockito.verify(refreshTokenRepository).findIdsForRetentionCleanup(any(), any());
    }

    private List<Long> fakeIds(int size) {
        List<Long> ids = new ArrayList<>(size);
        for (long i = 0; i < size; i++) {
            ids.add(i);
        }
        return ids;
    }
}
