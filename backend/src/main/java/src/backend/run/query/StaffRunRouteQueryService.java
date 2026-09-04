package src.backend.run.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.StaffAssignmentAckView;
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
 * 관계자용 확정 노선 조회(API_SPEC §5.19 {@code GET /staff/runs/{runId}/route}, RTE-02, F1 S3 목표
 * 11).
 *
 * <p>존재 판정과 학원 범위 판정을 분리한다({@code RosterQueryService#staffRoster} 와 같은 근거) —
 * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
 * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다.
 *
 * <p>{@code 409 RUN_NOT_CONFIRMED} 는 배포된 확정 노선(route_version)이 없을 때다(과업 지시서 판단
 * 근거) — 매니저용 {@link RunRouteQueryService#route} 는 같은 상태에서 빈 200 을 돌려주지만(운행 중
 * 단말의 일시적 공백을 허용), 관계자는 확정 노선을 "보는" 용도라 버전 부재 자체가 아직 볼 것이
 * 없다는 뜻이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffRunRouteQueryService {

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunRouteQueryService runRouteQueryService;

    public StaffRunRouteResponse route(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());

        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        // 방어적 조회 — currentVersionId 가 가리키는 route_version 행은 배포 절차상 항상 존재해야
        // 하지만(순환 FK 쌍이라 DB 제약으로는 못 막는다), 없으면 "볼 확정 노선이 없다"는 결론은
        // 같으므로 별도 오류 코드를 새로 만들지 않고 같은 409 로 묶는다.
        RouteVersion version = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));

        RunRouteResponse base = runRouteQueryService.buildFromVersion(requester, run, currentVersionId);
        Ack ack = ackOf(requester, runId);

        return new StaffRunRouteResponse(base.stops(), base.currentStop(), base.nextStop(), base.skippedNotice(),
                version.getVersionNo(), version.getPublishedAt(), ack);
    }

    /** {@code Assignment.ackedRouteVersionId == 현재 확정 버전} — 대시보드가 쓰는 것과 같은 비교(§5.3, {@code StaffDashboardQueryService#ackedOf} 참고). */
    private Ack ackOf(AuthUser requester, Long runId) {
        List<StaffAssignmentAckView> views = assignmentRepository
                .findAckViewsForStaffDashboard(requester.academyId(), List.of(runId));
        return new Ack(ackedOf(views, ManagerRole.DRIVER), ackedOf(views, ManagerRole.ESCORT));
    }

    private boolean ackedOf(List<StaffAssignmentAckView> views, ManagerRole role) {
        return views.stream().filter(view -> view.role() == role).findFirst()
                .map(StaffAssignmentAckView::acked).orElse(false);
    }
}
