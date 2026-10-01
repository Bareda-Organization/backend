package src.backend.location.scheduler;

import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.location.proximity.ProximityNotificationService;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 근접 알림 배치의 진입점(NTF-04, 목표 13·15) — 운행 중인 회차를 모아
 * {@code ProximityNotificationService#judgeOne} 을 회차별로 부른다.
 *
 * <p>{@code RunConfirmationScheduler} 와 달리 {@code CompletableFuture}·전용 스레드 풀을 쓰지
 * 않는다 — 그쪽이 비동기로 나누는 이유는 회차별 확정이 외부 지도 API 를 부르는 <b>느린 I/O</b>이기
 * 때문인데, 이쪽은 로컬 Redis 단건 읽기뿐이라 그 근거가 성립하지 않는다. 회차 하나가 예외를 던져도
 * 순차 {@code try-catch} 로 다음 회차로 넘어가는 것으로 충분하다(확정 배치와 같은 격리 근거,
 * 비동기 수단만 다르다).
 *
 * <p><b>한 회차의 실패가 다른 회차를 막지 않는다</b> — 실패한 회차는 이번 틱에서 아무 것도 갱신하지
 * 못한 채 다음 틱에 다시 대상이 된다(그 회차의 트랜잭션이 롤백되어 {@code proximity_notified_at}
 * 은 변화가 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProximityNotificationScheduler {

    /**
     * 한 번 조회로 읽는 크기 — 동시 운행 상한이 아니다. 한 틱은 이 크기씩 끝까지 이어 읽어 운행 중
     * 회차 <b>전부</b>를 판정한다(BR-011 — 0쪽만 집으면 51번째 이후 회차는 운행 내내 판정되지 않았다).
     */
    static final int BATCH_SIZE = 50;

    private final RunRepository runRepository;

    private final ProximityNotificationService proximityNotificationService;

    private final SchedulerHealthMetrics schedulerHealthMetrics;

    /** Redis 가 죽으면 운행 중 회차마다 틱마다 같은 실패가 난다 — 판정별로 분당 한 번만 스택과 함께 남긴다(R46 S-3). */
    private final RateLimitedWarn judgeFailure = RateLimitedWarn.perMinute(log);

    private final RateLimitedWarn departureFailure = RateLimitedWarn.perMinute(log);

    /**
     * 운행 중인 회차마다 근접 판정을 시도한다.
     *
     * <p>폴링 주기를 설정으로 받는 이유는 확정 배치와 같다 — 테스트에서 배경 실행을 미뤄야
     * 판정 건수·멱등성 단언이 실행 순서에 흔들리지 않는다({@code build.gradle} 이
     * {@code initial-delay-ms} 를 하루로 늦춘다).
     *
     * <p>{@code @SchedulerLock}(TECH_DECISIONS §3.2) — 인스턴스 2개가 같은 틱을 동시에 돌면
     * 락을 놓친 쪽은 {@code judgeOne} 을 한 번도 부르지 않고 돌아간다. {@code lockAtMostFor} 를
     * 폴링 주기(10초)의 3배로 잡은 이유는, 그 시간을 넘겨도 락이 안 풀리면 죽은 인스턴스가 락을
     * 들고 있다고 보고 다음 인스턴스가 이어받게 하려는 것이다(목표 13) — 정상 틱은 로컬 Redis
     * 단건 읽기만 반복하므로 이 상한에 걸릴 일이 없다. {@code lockAtLeastFor} 는 두지 않는다 —
     * 최솟값을 두면 같은 시험 클래스 안의 서로 다른 시험 메서드가 짧은 간격으로 이 메서드를
     * 각각 부를 때 뒤쪽 호출이 조용히 건너뛰어질 수 있다.
     */
    @Scheduled(fixedDelayString = "${app.location.proximity.poll-interval-ms:10000}",
            initialDelayString = "${app.location.proximity.initial-delay-ms:0}")
    @SchedulerLock(name = "proximity-notification", lockAtMostFor = "PT30S")
    public void judgeMovingRuns() {
        long afterId = 0;
        List<Run> page;
        do {
            page = runRepository.findByStatusAndCanceledAtIsNullAndIdGreaterThanOrderByIdAsc(RunStatus.MOVING,
                    afterId, PageRequest.of(0, BATCH_SIZE));
            for (Run run : page) {
                judgeSafely(run.getId(), run.getAcademyId());
                afterId = run.getId();
            }
        } while (page.size() == BATCH_SIZE);
    }

    /**
     * 회차 1건을 판정하고, 무엇이 됐든 실패를 삼켜 다음 회차로 넘어간다(확정 배치의
     * {@code confirmSafely} 와 같은 근거 — {@link Exception} 전체를 잡지 않으면 예상 못 한 실패가
     * 이 틱의 나머지 회차 판정까지 막는다). 근접·출발(Ruling 307, R14-T2 목표 6a) 두 판정을 각자
     * 독립된 try-catch 로 감싼다 — 한쪽이 실패해도 다른 쪽은 이번 틱에 그대로 시도돼야 한다.
     */
    private void judgeSafely(Long runId, Long academyId) {
        try {
            proximityNotificationService.judgeOne(runId, academyId);
        } catch (Exception e) {
            judgeFailure.warn(e, "회차 {} 근접 판정 실패 — 다음 틱에 재시도한다", runId);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
        try {
            proximityNotificationService.judgeDeparture(runId, academyId);
        } catch (Exception e) {
            departureFailure.warn(e, "회차 {} 출발 판정 실패 — 다음 틱에 재시도한다", runId);
            schedulerHealthMetrics.recordItemFailure(getClass());
        }
    }
}
