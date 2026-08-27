package src.backend.run.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.AssignedManagerResponse;
import src.backend.manager.dto.AssignedManagerView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.run.dto.RunResponse;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 그 날짜의 회차 목록(SCH-02 결과 조회, API_SPEC §5.10 {@code GET /staff/runs}).
 *
 * <p>{@code §5.18 GET /staff/runs/live}(MON-07, Phase 13)와 <b>다른 것</b>이다 — 이쪽은 날짜로 보는
 * 회차 목록이고 저쪽은 관제용 실시간 스냅샷이다. 경로가 비슷해도 합치지 않는다(Ruling 153).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunQueryService {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final AssignmentRepository assignmentRepository;

    private final Clock clock;

    /**
     * 소속 학원의 그날 회차 전부 — 날짜를 주지 않으면 <b>오늘</b>이다.
     *
     * <p>오늘의 판정을 주입된 {@code Clock} 에서 한다(횡단 규칙 1) — 시스템 시계를 직접 부르면
     * 시계를 고정한 테스트에서도 이 경로만 실제 날짜를 본다.
     *
     * <p>페이징을 두지 않는다 — 한 학원의 하루 회차는 차량 수 × 방향 2 규모라 전량 반환이 맞고,
     * 실시간 현황·운행 명단이 페이징 대상 밖인 것과 같은 기준이다(§1.8).
     *
     * @param requestedDate {@code YYYY-MM-DD}. {@code null} 이면 오늘
     */
    public List<RunResponse> list(AuthUser requester, String requestedDate) {
        LocalDate serviceDate = requestedDate == null ? LocalDate.now(clock) : ApiValues.date(requestedDate);
        List<Run> runs = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(
                requester.academyId(), serviceDate);
        if (runs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> busNos = busNosOf(requester, runs);
        Map<Long, List<AssignedManagerResponse>> assignments = assignmentsOf(requester, runs);
        return runs.stream()
                .map(run -> RunResponse.of(run, busNos.get(run.getBusId()),
                        assignments.getOrDefault(run.getId(), List.of())))
                .toList();
    }

    /** 이 목록이 참조하는 차량의 호차를 한 번에 읽는다 — 행마다 조회하면 목록 하나가 질의 N+1 개가 된다. */
    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    /** 이 목록의 배치를 회차별로 묶어 한 번에 읽는다 — 배치 화면이 결과를 되읽는 경로다(§5.14). */
    private Map<Long, List<AssignedManagerResponse>> assignmentsOf(AuthUser requester, List<Run> runs) {
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        return assignmentRepository.findAssignedManagers(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(AssignedManagerView::runId,
                        Collectors.mapping(AssignedManagerView::toResponse, Collectors.toList())));
    }
}
