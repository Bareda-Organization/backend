package src.backend.run.navigation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.AssignedManagerAccountView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.navigation.dto.NavDestination;
import src.backend.run.navigation.dto.NavOrigin;
import src.backend.run.navigation.dto.NavStopRow;
import src.backend.run.navigation.dto.NavWaypoint;
import src.backend.run.navigation.dto.NavigationResponse;
import src.backend.run.navigation.repository.NavRunStopRepository;
import src.backend.run.navigation.spec.NavProvider;
import src.backend.run.repository.RunRepository;

/**
 * 확정 노선을 외부 내비 앱이 쓸 좌표열로 조립한다(API_SPEC §4.16, RUN-08) — 순서 확정·제외
 * (스킵·도착 완료)·공급자 상한 자르기까지 서버가 하고, 딥링크 조립은 앱 몫이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NavigationQueryService {

    private final RunRepository runRepository;

    private final AssignmentRepository assignmentRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final NavRunStopRepository navRunStopRepository;

    private final AcademyRepository academyRepository;

    private final NavProvider navProvider;

    /**
     * {@code scope=next} 면 다음 목적지 1개, {@code scope=remaining} 이면 공급자 상한까지 채운
     * 남은 전 구간을 돌려준다.
     *
     * <p><b>배치 판정 순서(§1.11, Ruling 259(b))</b> — {@link #assertAssigned} 를 회차 조회보다 먼저
     * 부른다. 배치는 매니저·회차가 같은 학원일 때만 생성되므로({@code AssignmentCommandService#place}),
     * 배치가 없다는 사실 하나로 회차 없음·타 학원·미배치 세 경우가 구별 없이 {@code 403 FORBIDDEN} 이
     * 된다 — 그 뒤의 {@code findByIdAndAcademyId} 는 도달할 일이 거의 없는 방어 조회로 남는다.
     *
     * @throws BusinessException {@code FORBIDDEN}(회차 없음·타 학원·배치 안 된 기사·동승자 공통) ·
     *     {@code RUN_NOT_FOUND}(방어 조회, 정상 흐름에서는 도달하지 않는다) ·
     *     {@code RUN_NOT_CONFIRMED}(idle) · {@code NAV_NO_REMAINING_STOP}
     *     (스킵·도착 완료 제외 후 남은 승하차지 0건)
     */
    public NavigationResponse navigate(AuthUser requester, Long runId, NavigationScope scope) {
        assertAssigned(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }

        List<NavStopRow> remaining = remainingStops(run);
        if (remaining.isEmpty()) {
            throw new BusinessException(ErrorCode.NAV_NO_REMAINING_STOP);
        }

        int totalRemainingStops = remaining.size();
        List<NavStopRow> selected = select(remaining, scope);
        boolean truncated = selected.size() < totalRemainingStops && scope == NavigationScope.REMAINING;

        return new NavigationResponse(providerName(), originOf(run), waypointsOf(selected), destinationOf(selected),
                truncated, truncated ? "경유지가 많아 일부만 표시합니다" : null, totalRemainingStops);
    }

    /**
     * 배치되지 않은 기사·동승자는 이 회차에 접근할 수 없다(§4.16 권한, §4.3 과 같은 범위).
     *
     * <p>{@code run} 이 아니라 {@code requester.academyId()} 로 조회한다 — 회차 조회보다 먼저 불러야
     * 하므로 아직 회차 학원을 모른다. 배치는 매니저·회차가 같은 학원일 때만 생성되므로, 요청자 학원
     * 기준으로 걸러도 정보가 새지 않는다(round-1 {@code ManagerRunAccess} 와 같은 근거).
     */
    private void assertAssigned(AuthUser requester, Long runId) {
        boolean assigned = assignmentRepository.findAssignedManagerAccounts(requester.academyId(), runId)
                .stream()
                .map(AssignedManagerAccountView::accountId)
                .anyMatch(requester.accountId()::equals);
        if (!assigned) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * {@code skipped} 와 도착 완료분을 뺀 나머지를 순번 그대로 돌려준다(§4.16 두 제외 규칙) —
     * 확정 노선 배포 자체가 없으면(비정상 상태) 빈 목록으로 다뤄 {@code NAV_NO_REMAINING_STOP} 로
     * 수렴시킨다.
     */
    private List<NavStopRow> remainingStops(Run run) {
        Long versionId = currentRouteVersionId(run.getId());
        if (versionId == null) {
            return List.of();
        }
        return navRunStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeqAsc(versionId, run.getAcademyId())
                .stream()
                .filter(row -> row.change() != ChangeType.SKIPPED)
                .filter(row -> !row.arrived())
                .toList();
    }

    private Long currentRouteVersionId(Long runId) {
        return confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId).orElse(null);
    }

    /** {@code next} 는 1개만, {@code remaining} 은 공급자 상한까지 자른다(목표 19 — 개수는 여기서만 자른다). */
    private List<NavStopRow> select(List<NavStopRow> remaining, NavigationScope scope) {
        int limit = scope == NavigationScope.NEXT ? 1 : navProvider.maxStops();
        return remaining.size() <= limit ? remaining : remaining.subList(0, limit);
    }

    /** 마지막 항목이 목적지, 그 앞이 경유지다 — 배열 순서가 곧 주행 순서라는 계약(§4.16)을 그대로 쓴다. */
    private List<NavWaypoint> waypointsOf(List<NavStopRow> selected) {
        List<NavWaypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < selected.size() - 1; i++) {
            NavStopRow row = selected.get(i);
            waypoints.add(new NavWaypoint(row.lat(), row.lng(), row.name(), row.stopId(), row.seq()));
        }
        return waypoints;
    }

    private NavDestination destinationOf(List<NavStopRow> selected) {
        NavStopRow last = selected.get(selected.size() - 1);
        return new NavDestination(last.lat(), last.lng(), last.name(), last.stopId());
    }

    /**
     * {@code confirmed} 에서만 채운다(X-01, Ruling 202) — {@code moving}·{@code finished} 는 앱이
     * 실측 GPS 를 쓰므로 부재해야 한다(목표 20의 역방향 갈래).
     */
    private NavOrigin originOf(Run run) {
        if (run.getStatus() != RunStatus.CONFIRMED) {
            return null;
        }
        return academyRepository.findById(run.getAcademyId())
                .filter(Academy::hasCoordinates)
                .map(academy -> new NavOrigin(academy.getLat(), academy.getLng(), run.getOriginName()))
                .orElse(null);
    }

    private String providerName() {
        return navProvider.name().name().toLowerCase(Locale.ROOT);
    }
}
