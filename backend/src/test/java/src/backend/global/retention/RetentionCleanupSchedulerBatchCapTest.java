package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.location.repository.RunPositionRepository;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.LinkRequestRepository;

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
    private final RunPositionRepository runPositionRepository = mock(RunPositionRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final LinkCodeRepository linkCodeRepository = mock(LinkCodeRepository.class);
    private final LinkRequestRepository linkRequestRepository = mock(LinkRequestRepository.class);

    @Test
    void 매_조회_호출마다_배치_상한을_그대로_넘긴다() {
        // 다른 4개 테이블은 빈 목록만 반환해 이 시험이 notification_log 호출에만 집중하게 한다.
        when(runPositionRepository.findIdsForRetentionCleanup(any(), any())).thenReturn(List.of());
        when(refreshTokenRepository.findIdsForRetentionCleanup(any(), any())).thenReturn(List.of());
        when(linkCodeRepository.findIdsForRetentionCleanup(any(), any())).thenReturn(List.of());
        when(linkRequestRepository.findIdsForRetentionCleanup(any(), any())).thenReturn(List.of());

        // notification_log 는 2회차에 걸쳐 지워지도록 1회차엔 상한만큼, 2회차엔 그보다 적게 돌려준다 —
        // 그래야 "매 호출마다" 상한이 유지되는지(1회차만 우연히 맞고 2회차부터 새는 결함도) 잡힌다.
        List<Long> firstRound = fakeIds(RetentionPolicy.BATCH_SIZE);
        List<Long> secondRound = fakeIds(3);
        when(notificationLogRepository.findIdsForRetentionCleanup(any(), any()))
                .thenReturn(firstRound)
                .thenReturn(secondRound);

        RetentionCleanupScheduler scheduler = new RetentionCleanupScheduler(
                notificationLogRepository, runPositionRepository, refreshTokenRepository,
                linkCodeRepository, linkRequestRepository, retentionPolicy, clock);

        scheduler.cleanUp();

        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        org.mockito.Mockito.verify(notificationLogRepository, org.mockito.Mockito.times(2))
                .findIdsForRetentionCleanup(any(OffsetDateTime.class), limitCaptor.capture());

        assertThat(limitCaptor.getAllValues())
                .as("조회 2회 전부 배치 상한(%s)을 그대로 넘겨야 한다 — 상한이 빠지면(예: Limit.unlimited()) "
                        + "1회차에 전건을 긁어와 다회차 배치가 성립하지 않는다", RetentionPolicy.BATCH_SIZE)
                .allSatisfy(limit -> assertThat(limit).isEqualTo(Limit.of(RetentionPolicy.BATCH_SIZE)));
    }

    private List<Long> fakeIds(int size) {
        List<Long> ids = new ArrayList<>(size);
        for (long i = 0; i < size; i++) {
            ids.add(i);
        }
        return ids;
    }
}
