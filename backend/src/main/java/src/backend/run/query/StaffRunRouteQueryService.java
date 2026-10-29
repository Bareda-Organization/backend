package src.backend.run.query;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.global.location.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.dto.StaffRunRouteResponse;
import src.backend.run.dto.StaffRunRouteResponse.Ack;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 관계자용 확정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route}, RTE-02).
 *
 * <p>존재 판정과 학원 범위 판정을 분리한다({@code RosterQueryService#staffRoster} 와 같은 근거) —
 * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
 * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다.
 *
 * <p>{@code 409 RUN_NOT_CONFIRMED} 는 배포된 확정 노선(route_version)도 없고 <b>고정 노선으로 낼
 * 예정 경로도 없을 때</b>다(Ruling 321) — 매니저용 {@link RunRouteQueryService#route} 는 같은
 * 상태에서 빈 200 을 돌려주지만(운행 중 단말의 일시적 공백을 허용), 관계자는 확정 노선을 "보는"
 * 용도라 둘 다 없으면 아직 볼 것이 없다는 뜻이다.
 *
 * <p><b>클래스에 트랜잭션을 걸지 않는다</b>(BR-046) — 예정 경로 계산이 외부 지도 API 를 부르므로, 그 안에
 * 있으면 지도가 느린 만큼 DB 커넥션을 쥔 채 기다린다({@code RouteComputationPipeline} 자바독과 같은 근거).
 * 조회는 저장소 호출마다 짧은 읽기 트랜잭션으로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class StaffRunRouteQueryService {

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRouteQueryService runRouteQueryService;

    private final StaffPlannedRouteAssembler plannedRouteAssembler;

    /** 확정 노선이 있으면 그대로, 없으면 예정 경로({@link StaffPlannedRouteAssembler#plannedRouteOf})로 폴백해 조회한다(§5.19). */
    public StaffRunRouteResponse route(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());

        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return plannedRouteAssembler.plannedRouteOf(run)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));
        }
        return confirmedRouteOf(run, currentVersionId);
    }

    /** 배포된 확정 버전의 노선에 버전 번호·배포 시각·확인 현황을 더해 조립한다. */
    private StaffRunRouteResponse confirmedRouteOf(Run run, Long currentVersionId) {
        // 방어적 조회 — currentVersionId 가 가리키는 route_version 행은 배포 절차상 항상 존재해야
        // 하지만(순환 FK 쌍이라 DB 제약으로는 못 막는다), 없으면 "볼 확정 노선이 없다"는 결론은
        // 같으므로 별도 오류 코드를 새로 만들지 않고 같은 409 로 묶는다.
        RouteVersion version = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));

        RunRouteResponse base = runRouteQueryService.buildFromVersion(run, currentVersionId);
        Ack ack = ackOf(run.getAcademyId(), run.getId());
        List<GeoPoint> roadPath = version.getRoadPath() == null ? List.of() : version.getRoadPath();

        return new StaffRunRouteResponse(base.stops(), base.currentStop(), base.nextStop(), base.skippedNotice(),
                version.getVersionNo(), version.getPublishedAt(), ack, roadPath, version.isFallbackUsed(), true);
    }

    /**
     * {@code Assignment.ackedRouteVersionId == 현재 확정 버전} — 대시보드가 쓰는 것과 같은 비교(§5.3,
     * {@code StaffDashboardQueryService#ackedOf} 참고).
     *
     * <p>요청자의 {@code academyId} 가 아니라 <b>회차가 속한 학원</b>으로 찾는다. 메인 관리자는
     * {@code academyId} 가 {@code null} 이라(그 역할만 null 이 허용된다, {@link AuthUser}) 요청자 기준으로
     * 찾으면 일치하는 행이 없어 확인 현황이 늘 "둘 다 미확인" 으로 나온다 — 권한이 없어서가 아니라
     * 조회 조건이 안 맞아서인데, 빈 결과라 구별할 수단이 부재하다. 이 지점에 닿기 전에
     * {@code AcademyScope#assertAccessible} 이 이미 접근을 판정했으므로 회차의 학원을 그대로 쓰는 것이
     * 두 역할 모두에게 옳다.
     */
    private Ack ackOf(Long academyId, Long runId) {
        List<StaffAssignmentAckView> views = assignmentRepository
                .findAckViewsForStaffDashboard(academyId, List.of(runId));
        return new Ack(ackedOf(views, ManagerRole.DRIVER), ackedOf(views, ManagerRole.ESCORT));
    }

    private boolean ackedOf(List<StaffAssignmentAckView> views, ManagerRole role) {
        return views.stream().filter(view -> view.role() == role).findFirst()
                .map(StaffAssignmentAckView::acked).orElse(false);
    }
}
