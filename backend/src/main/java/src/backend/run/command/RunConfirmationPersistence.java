package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.RunRiderPersistence;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.command.RouteVersionDeploymentService;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.domain.RunRouteEndpoints;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.entity.RunTransfer;
import src.backend.run.entity.RunTransferStatus;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;

/**
 * 확정 배치의 짧은 쓰기 트랜잭션 — {@link RunConfirmationService#confirmOne} 이 트랜잭션 밖에서
 * 계산까지 마친 결과를 받아 "확정 표시 + 4종 산출물 저장 + 동승자 자동 배정 + 이벤트 발행" 만 한 트랜잭션으로 묶는다.
 *
 * <p>별도 빈으로 분리한 이유는 self-invocation 때문이다 — {@code RunConfirmationService} 안의
 * 메서드로 두면 그 클래스 자신을 통한 호출이라 {@code @Transactional} 프록시를 거치지 않는다.
 *
 * <p><b>{@link #persist} 도중 어디서 예외가 나든 이 트랜잭션 전체가 롤백된다</b> — 그러면
 * {@link RunRepository#confirmIfIdle} 이 표시한 {@code idle → confirmed} 전이도 함께 취소되어 회차가
 * 자동으로 idle 로 되돌아간다(목표 5). 이벤트도 트랜잭션 커밋 안에서만 발행되므로 롤백된 확정에
 * 대해서는 발행되지 않는다({@link RunRouteConfirmedEvent} javadoc).
 */
@Component
@RequiredArgsConstructor
@Transactional
public class RunConfirmationPersistence {

    private final RunRepository runRepository;

    /** 확정 노선(v1) 저장 진입점(BR-094·095) — {@code routing} 소유 테이블에 직접 쓰지 않는다. */
    private final RouteVersionDeploymentService routeVersionDeploymentService;

    /** 최초 탑승자 명단 저장 진입점(BR-095) — {@code boarding} 소유 테이블에 직접 쓰지 않는다. */
    private final RunRiderPersistence runRiderPersistence;

    private final RunTransferRepository runTransferRepository;

    private final ProjectedRosterReader rosterReader;

    private final AttendantAutoAssignment attendantAutoAssignment;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 회차를 확정하고 4종 산출물({@code confirmed_route}·{@code route_version}·{@code run_stop}·
     * {@code run_rider})을 저장한다. {@code roster.absentStops()} 는 노선 계산에서 뺀 ①구간 OFF 학생으로,
     * {@code absent} 행으로만 남는다(FEATURE_SPEC §3.3). 도착 이동 대기 건은 이 트랜잭션 안에서
     * {@code applied} 로 표시한다 — 확정이 롤백되면 표시도 함께 되돌아가야 하고, 출발 회차 쪽 확정은
     * 표시하지 않는다(도착 회차 확정 전까지 그 학생은 도착 회차의 대기 인원이다, BR-093).
     *
     * <p><b>{@code confirmIfIdle} 이 0행을 갱신하면 그 자리에서 조용히 반환한다</b>(목표 2) — 동시
     * 스레드 중 나중 것이 진 경우다. 이미 진 경쟁에서 계산 결과를 그대로 버리는 것이 맞다 — 먼저
     * 확정한 스레드의 결과와 이 결과가 같은 입력에서 나왔다는 보장이 없어(그 사이 명단이 바뀌었을 수
     * 있다), 대신 넣으면 먼저 커밋된 버전을 뒤엎는 꼴이 된다.
     *
     * @return 이 호출이 실제로 확정을 저장했으면 {@code true}, 진 경쟁이라 조용히 반환했으면
     *         {@code false} — 호출부({@code RunConfirmationService#confirmOne})가 이 값으로 배치
     *         지연 지표(목표 8)를 승자 1건에만 기록한다. 반환값이 없던 시절엔 진 스레드도 이 지점까지
     *         정상 도달해 지표 표본이 확정 사건 수보다 부풀었다 — 이 지표의 존재 목적(증설 판단 신호,
     *         {@code ARCHITECTURE §9.4})이 인스턴스를 늘려 경합이 잦아지는 바로 그 시점에 가장
     *         부정확해지는 결함이었다.
     */
    public boolean persist(Run run, RouteComputation computation, RunRouteEndpoints.Endpoints endpoints,
            Weekday weekday, ProjectedRoster roster, OffsetDateTime confirmedAt) {
        int updated = runRepository.confirmIfIdle(run.getId(), confirmedAt);
        if (updated == 0) {
            return false;
        }
        // confirmIfIdle 이 잡은 행 잠금 뒤라 이후 강제 추가·이동 저장은 confirmed 를 보고 막힌다(StagingRunGuard).
        // 그 전에 들어오거나 취소된 행은 여기서 id 로 대조해 이번 확정을 무른다 — 롤백되면 idle 로 남아 다음 틱이 다시 읽는다.
        if (!rosterReader.stagedRows(run).equals(roster.stagedRows())) {
            throw new IllegalStateException("확정 계산 중 강제 추가·이동이 바뀌었다 — 다음 틱에 다시 읽는다: runId="
                    + run.getId());
        }

        String fingerprint = RunConfirmationFingerprint.of(run.getAcademyId(), weekday, run.getDirection(),
                run.getDepartTime(), endpoints.origin(), endpoints.destination(), roster.studentStops(), List.of());
        routeVersionDeploymentService.confirmInitial(run, computation, fingerprint, confirmedAt);
        runRiderPersistence.confirmRiders(run.getId(), roster, computation.unresolvedStudentIds(), confirmedAt);
        if (!roster.incomingTransfers().isEmpty()) {
            runTransferRepository.markApplied(roster.incomingTransfers().stream().map(RunTransfer::getId).toList(),
                    confirmedAt, RunTransferStatus.APPLIED);
        }

        // 노선 확정 알림보다 먼저 — 자동 배정된 동승자도 확정 노선 알림(route_changed)의 수신자가 된다.
        attendantAutoAssignment.assignIfVacant(run, computation.estDurationMin(), confirmedAt);

        eventPublisher.publishEvent(
                new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), confirmedAt));

        return true;
    }
}
