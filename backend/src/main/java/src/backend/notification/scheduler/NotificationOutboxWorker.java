package src.backend.notification.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.notification.command.NotificationDispatcher;
import src.backend.notification.domain.NotificationRetryPolicy;
import src.backend.notification.entity.NotificationLog;
import src.backend.notification.entity.PushState;
import src.backend.notification.repository.NotificationLogRepository;
import src.backend.observability.metrics.NotificationPushMetrics;

/**
 * 아웃박스 안전망(TECH_DECISIONS §7.2 ②) — 커밋 직후 즉시 발송이 실패했거나 그 사이 앱이 죽어
 * 누락된 {@code pending} 행을 주기적으로 회수해 다시 보낸다.
 *
 * <p>발송 절차 자체는 {@link NotificationDispatcher} 를 그대로 쓴다 — 즉시 발송과 다른 코드를 두면
 * 상한·사유 기록·상태 전이가 두 벌이 되고, 한쪽만 고쳤을 때 양쪽 테스트가 계속 통과한다.
 *
 * <p>회수 대상을 <b>{@code pending} 으로 좁히는 것</b>이 이 클래스의 전부다. 조건이 빠지면 이미 보낸
 * 알림까지 전부 재발송되는데, 그 워커도 "미발송분이 발송된다" 는 그대로 통과한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutboxWorker {

    /**
     * 한 틱에 집는 상한 — 미발송이 쌓였을 때 한 번에 전부 읽어 힙과 발송 채널을 함께 밀어붙이는 것을
     * 막는다. 남은 건은 다음 틱이 가져가므로 유실은 부재하다.
     */
    private static final int BATCH_SIZE = 100;

    private final NotificationLogRepository notificationLogRepository;

    private final NotificationDispatcher notificationDispatcher;

    private final NotificationRetryPolicy retryPolicy;

    private final Clock clock;

    private final NotificationPushMetrics pushMetrics;

    /**
     * 미발송분을 회수해 재발송한다.
     *
     * <p>폴링 주기와 최초 지연을 설정으로 받는 이유는 <b>테스트에서 배경 실행을 미루기 위함</b>이다 —
     * 배경 워커가 테스트보다 먼저 행을 집으면 회수·선점 검증이 실행 순서에 따라 갈린다
     * ({@code build.gradle} 의 {@code app.notification.outbox.initial-delay-ms}).
     *
     * <p>회차별 지수 백오프를 SQL 이 아니라 여기서 거르는 이유는 간격이 시도 횟수에 따라 달라
     * 파라미터 하나로 표현할 수단이 부재하기 때문이다 — 질의는 최소 간격까지만 좁힌다.
     *
     * <p>{@code @SchedulerLock}(TECH_DECISIONS §3.2) — {@link NotificationDispatcher#dispatch} 의
     * 조건부 UPDATE 선점이 이미 이중 발송을 막고 있지만, 락이 없으면 인스턴스 수만큼 같은 배치를
     * 반복 조회·시도해 DB 부하만 늘어난다(TECH_DECISIONS 가 든 예시가 이 워커다). {@code lockAtMostFor}
     * 를 폴링 주기(30초)의 4배로 잡았다 — 죽은 인스턴스가 있어도 2분 안에는 다음 인스턴스가 이어받는다.
     */
    @Scheduled(fixedDelayString = "${app.notification.outbox.poll-interval-ms:30000}",
            initialDelayString = "${app.notification.outbox.initial-delay-ms:0}")
    @SchedulerLock(name = "notification-outbox-worker", lockAtMostFor = "PT2M")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<NotificationLog> candidates = notificationLogRepository.findRetryCandidates(
                PushState.PENDING, NotificationRetryPolicy.MAX_ATTEMPTS, retryPolicy.attemptedBefore(now),
                PageRequest.of(0, BATCH_SIZE));

        candidates.stream()
                .filter(candidate -> retryPolicy.retryDue(candidate, now))
                .forEach(this::dispatchAndObserve);
    }

    /**
     * {@code dispatch} 는 반환값이 없어 소진 여부를 알 수 없다({@code NotificationDispatcher} 는
     * 범위 밖) — {@code markAttemptFailed} 가 이미 {@code REQUIRES_NEW} 로 커밋한 뒤라 재조회하면
     * 확정된 결과를 본다.
     */
    private void dispatchAndObserve(NotificationLog candidate) {
        notificationDispatcher.dispatch(candidate.getId());
        notificationLogRepository.findById(candidate.getId())
                .filter(log -> log.getPushState() == PushState.FAILED)
                .ifPresent(log -> pushMetrics.recordFailure());
    }
}
