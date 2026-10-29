package src.backend.run.roster;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Weekday;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 전(idle) 회차의 예정 명단 — 확정 배치(RTE-08)가 그 회차 {@code run_rider} 에 싣는 규칙 한 벌.
 *
 * <p>정원 판정(BUS-04)의 강제 추가(§5.7)·버스 간 이동(§5.8)도 이 규칙을 쓴다 — 경로마다 명단을 따로
 * 조립하면 한쪽이 대기 중인 이동이나 OFF 학생을 빠뜨려 정원 초과를 막지 못한다(BR-043).
 *
 * <p>규칙 — 고정 노선 정차지의 요일별 주소 학생 − ①구간 OFF + 강제 추가 − 출발 이동 + 도착 이동.
 * 이동 행은 상태로 거르지 않는다({@link RunTransfer} 자바독의 자기 치유).
 */
@Component
@RequiredArgsConstructor
public class ProjectedRosterReader {

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    private final RunTransferRepository runTransferRepository;

    private final RosterBatchLoader batchLoader;

    /** 운행일 0시를 서비스 시간대로 얻는다 — 퇴원생 제외(BR-003)의 기준 시각. */
    private final Clock clock;

    /** 고정 노선을 찾아 읽는다 — 노선이 없으면 요일별 주소 몫은 0명이다(강제 추가·이동만 남는다). */
    public ProjectedRoster read(Run run) {
        Weekday weekday = Weekday.of(run.getServiceDate());
        List<Long> stopIds = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .map(route -> routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                        run.getAcademyId()).stream().map(RouteStop::getStopId).toList())
                .orElse(List.of());
        return read(run, weekday, stopIds);
    }

    /** 확정 배치처럼 고정 노선의 정차지를 이미 읽은 호출부용. */
    public ProjectedRoster read(Run run, Weekday weekday, List<Long> stopIds) {
        List<StudentDailyStop> dailyStops = stopIds.isEmpty() ? List.of()
                : weeklyAddressRepository.findDailyStopsByStopIds(run.getAcademyId(), stopIds, weekday,
                        run.getDirection(), serviceDayStart(run));
        return RosterAssembler.assemble(new RosterSources(run, dailyStops,
                boardingIntentRepository.findStudentIdsByRunIdAndRidingFalse(run.getId()),
                changeRequestRepository.findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(
                        run.getAcademyId(), run.getId(), ChangeRequestType.RELOCATE, ChangeRequestStatus.APPROVED),
                runForcedAdditionRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByFromRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(), run.getAcademyId())));
    }

    /**
     * <b>같은 학원</b>(다르면 {@link IllegalArgumentException}) 회차 여러 개의 예정 명단을 <b>회차 수와 무관한 조회 횟수</b>로 읽는다(DA-7) — 노선 · 노선 정차지 · 요일별
     * 주소(방향별) · 탑승 의사 · 승인된 변경 · 강제 추가 · 이동을 회차 묶음으로 한 번씩 읽고({@link RosterBatchLoader}) 회차별로 가른 뒤 {@link #read} 와 같은
     * 규칙({@link RosterAssembler})으로 조립한다. 결과는 회차마다 {@link #read(Run)} 와 같다.
     */
    public Map<Long, ProjectedRoster> readAll(List<Run> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProjectedRoster> rosters = new HashMap<>();
        batchLoader.load(runs).forEach((runId, sources) -> rosters.put(runId, RosterAssembler.assemble(sources)));
        return rosters;
    }

    private OffsetDateTime serviceDayStart(Run run) {
        return run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
    }
}
