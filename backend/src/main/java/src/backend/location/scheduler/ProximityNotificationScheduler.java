package src.backend.location.scheduler;

import java.util.List;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.location.proximity.ProximityNotificationService;
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
     * 한 틱이 한 번에 집는 상한(확정 배치와 같은 근거) — 상한 없이 전건을 집으면 동시 운행 중인
     * 회차가 몰린 틱 하나가 오래 걸린다.
     */
    static final int BATCH_SIZE = 50;

    private final RunRepository runRepository;

    private final ProximityNotificationService proximityNotificationService;

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
        List<Run> movingRuns = runRepository.findByStatusAndCanceledAtIsNullOrderByIdAsc(RunStatus.MOVING,
                PageRequest.of(0, BATCH_SIZE));

        for (Run run : movingRuns) {
            judgeSafely(run.getId(), run.getAcademyId());
        }
    }

    /**
     * 회차 1건을 판정하고, 무엇이 됐든 실패를 삼켜 다음 회차로 넘어간다(확정 배치의
     * {@code confirmSafely} 와 같은 근거 — {@link Exception} 전체를 잡지 않으면 예상 못 한 실패가
     * 이 틱의 나머지 회차 판정까지 막는다).
     */
    private void judgeSafely(Long runId, Long academyId) {
        try {
            proximityNotificationService.judgeOne(runId, academyId);
        } catch (Exception e) {
            log.warn("회차 {} 근접 판정 실패 — 다음 틱에 재시도한다", runId, e);
        }
    }
}
