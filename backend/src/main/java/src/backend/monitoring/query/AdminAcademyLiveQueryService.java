package src.backend.monitoring.query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.AdminAcademyLiveResponse;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 메인 관리자 콘솔의 학원 1곳 실시간 관제 조회(API_SPEC §6.8, O-05, 목표 8·9).
 *
 * <p>{@code eta} 계열 값은 전부 {@code run_stop.eta} 저장값을 읽기만 한다 — 좌표·거리로 다시
 * 계산하지 않는다(Ruling 232 확정 — 계획값, 재계산 부재). 좌표는 T1 이 만든 공용
 * {@link RunLiveStateResolver}(§5.18·§6.8 공유, Phase 13 §2)를 그대로 쓴다 — {@code position} 은
 * API_SPEC §6.8 에 선택 필드(`○`)라 Redis 값이 없으면 객체 자체를 {@code null} 로 비운다(필드는
 * 있고 값만 비는 형태로 두지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAcademyLiveQueryService {

    private final AcademyRepository academyRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunLiveStateResolver runLiveStateResolver;

    public AdminAcademyLiveResponse live(Long academyId) {
        if (!academyRepository.existsById(academyId)) {
            throw new BusinessException(ErrorCode.ACADEMY_NOT_FOUND);
        }

        List<Run> movingRuns = runRepository.findAllByAcademyIdAndStatusOrderByDepartTimeAsc(academyId,
                RunStatus.MOVING);
        if (movingRuns.isEmpty()) {
            return new AdminAcademyLiveResponse(List.of());
        }

        Map<Long, String> busNoByBusId = busRepository
                .findAllByAcademyIdAndIdIn(academyId, movingRuns.stream().map(Run::getBusId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));

        List<Long> runIds = movingRuns.stream().map(Run::getId).toList();
        Map<Long, List<AssignedManagerContactView>> contactsByRunId = assignmentRepository
                .findAssignedManagerContacts(academyId, runIds).stream()
                .collect(Collectors.groupingBy(AssignedManagerContactView::runId));

        List<AdminAcademyLiveResponse.Run> runs = movingRuns.stream()
                .map(run -> toRun(run, busNoByBusId.get(run.getBusId()), contactsByRunId.getOrDefault(run.getId(),
                        List.of())))
                .toList();
        return new AdminAcademyLiveResponse(runs);
    }

    private AdminAcademyLiveResponse.Run toRun(Run run, String busNo, List<AssignedManagerContactView> contacts) {
        List<RunStop> ordered = orderedStopsOf(run);
        List<AdminAcademyLiveResponse.Stop> stops = ordered.stream()
                .filter(stop -> stop.getStopId() != null)
                .map(this::toStop)
                .toList();

        RunLiveState liveState = runLiveStateResolver.resolve(run);
        AdminAcademyLiveResponse.Position position = liveState.receivedAt() == null ? null
                : new AdminAcademyLiveResponse.Position(liveState.lat(), liveState.lng(), liveState.receivedAt());

        return new AdminAcademyLiveResponse.Run(run.getId(), busNo, lower(run.getDirection().name()),
                lower(run.getStatus().name()), position, run.getDepartTime(), run.getStartedAt(), stops,
                destinationEtaOf(run), contactOf(contacts, ManagerRole.DRIVER), contactOf(contacts,
                        ManagerRole.ESCORT));
    }

    /**
     * 목적지 도착 예정(Ruling 232 확정 — 계획값, 재계산 부재) = {@code depart_time + est_duration_min}.
     * {@code est_duration_min} 이 아직 없으면(노선 계산 전) {@code null} 이다 — moving 상태는 노선
     * 확정 이후에만 도달하므로 실제로는 거의 항상 채워져 있다(edge case, 보고서 §2 참고).
     */
    private OffsetDateTime destinationEtaOf(Run run) {
        if (run.getEstDurationMin() == null) {
            return null;
        }
        return run.getDepartTime().plusMinutes(run.getEstDurationMin());
    }

    private AdminAcademyLiveResponse.Contact contactOf(List<AssignedManagerContactView> contacts, ManagerRole role) {
        return contacts.stream()
                .filter(contact -> contact.role() == role)
                .findFirst()
                .map(contact -> new AdminAcademyLiveResponse.Contact(contact.name(), contact.phone()))
                .orElse(null);
    }

    /**
     * {@code eta} 는 {@code run_stop.eta} 저장값 그대로다(Ruling 232 확정). 도착 처리
     * ({@code arrived_at != null})면 이미 지난 예정이라 응답에서 비운다 — 저장값 자체를 지우는 것이
     * 아니라 이 조회가 읽을 때만 비운다.
     */
    private AdminAcademyLiveResponse.Stop toStop(RunStop runStop) {
        Stop stop = stopRepository.findById(runStop.getStopId()).orElse(null);
        OffsetDateTime eta = runStop.getArrivedAt() != null ? null : runStop.getEta();
        return new AdminAcademyLiveResponse.Stop(runStop.getStopId(), runStop.getSeq(),
                stop == null ? null : stop.getName(), stop == null ? null : stop.getLat(),
                stop == null ? null : stop.getLng(), runStop.getChange() == null ? null : lower(runStop.getChange()
                        .name()), runStop.getArrivedAt(), eta);
    }

    /**
     * {@code PositionBroadcastListener#currentStopNameOf} 와 같은 조회 경로(확정 노선 버전 →
     * 정차 순서)를 이 서비스 자신의 소유 범위 안에서 다시 계산한다 — 그 클래스를 직접 재사용하지
     * 않는 이유도 같다(소유 모듈이 다르다).
     */
    private List<RunStop> orderedStopsOf(Run run) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId()).orElse(null);
        if (confirmedRoute == null || confirmedRoute.getCurrentVersionId() == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(confirmedRoute.getCurrentVersionId(),
                run.getAcademyId());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
