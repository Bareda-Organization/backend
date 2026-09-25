package src.backend.run.roster;

import java.time.LocalDate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Weekday;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunForcedAddition;
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

    /** 운행일 0시를 서비스 시간대로 얻는다 — 퇴원생 제외(BR-003)의 기준 시각. */
    private final Clock clock;

    /** 고정 노선을 찾아 읽는다 — 노선이 없으면 요일별 주소 몫은 0명이다(강제 추가·이동만 남는다). */
    public ProjectedRoster read(Run run) {
        Weekday weekday = weekdayOf(run.getServiceDate());
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
                        run.getDirection(), run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime());
        // ①구간 탑승 의사 토글(riding=false) 학생은 노선 계산에서 빼고 absent 행으로만 남긴다 — absent 는
        // "확정 노선 산출 시점 부여" 이고 "미등원 N명"·관계자 명단(RST-03)이 그 행을 센다(FEATURE_SPEC §3.3).
        Set<Long> excludedStudentIds = new HashSet<>(
                boardingIntentRepository.findStudentIdsByRunIdAndRidingFalse(run.getId()));
        Map<Long, Long> absentStops = dailyStops.stream()
                .filter(stop -> excludedStudentIds.contains(stop.getStudentId()))
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));
        // 강제 추가 병합이 뒤에서 덧붙이므로 가변 목록으로 둔다(RTE-06).
        List<Long> studentIds = new ArrayList<>(
                dailyStops.stream().map(StudentDailyStop::getStudentId).distinct()
                        .filter(studentId -> !excludedStudentIds.contains(studentId))
                        .toList());
        Map<Long, Long> studentStops = dailyStops.stream()
                .filter(stop -> !excludedStudentIds.contains(stop.getStudentId()))
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));

        // P-06 일일 변경(승인된 경유지 이동)이 요일별 주소를 이긴다 — DailyStopResolver.studentToStop 과
        // 같은 우선순위(그 클래스 자바독). studentStops 에도 같은 값을 덮어써야 fingerprint·run_rider 가
        // 실제로 계산에 쓰인 경유지와 일치한다.
        Map<Long, Long> stopOverrides = new HashMap<>(changeRequestRepository
                .findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(run.getAcademyId(), run.getId(),
                        ChangeRequestType.RELOCATE, ChangeRequestStatus.APPROVED)
                .stream()
                .collect(Collectors.toMap(ChangeRequest::getStudentId, ChangeRequest::getNewStopId,
                        (first, last) -> last)));
        // 승인된 경유지 이동이 OFF 학생을 명단에 되살리지 않게 한다(BR-012).
        stopOverrides.keySet().removeAll(excludedStudentIds);

        Set<Long> weeklyStudentIds = Set.copyOf(studentIds);
        // ①구간 강제 추가(RTE-06, Ruling 197·198) — 요일별 주소에 없던 학생이라 studentIds 에도 더하고,
        // stopOverrides 에 넣어야 좌표 해석 단계(DailyStopResolver.studentToStop)가 그 정차지를 찾는다.
        List<RunForcedAddition> forcedAdditions = runForcedAdditionRepository.findAllByRunIdAndAcademyId(run.getId(),
                run.getAcademyId());
        for (RunForcedAddition forcedAddition : forcedAdditions) {
            if (!studentIds.contains(forcedAddition.getStudentId())) {
                studentIds.add(forcedAddition.getStudentId());
            }
            stopOverrides.put(forcedAddition.getStudentId(), forcedAddition.getStopId());
        }

        // 버스 간 이동(RTE-07, API_SPEC §5.8, Ruling 256) — 출발이면 빼고 도착이면 더한다. 출발 쪽은
        // studentStops·stopOverrides 양쪽에서 지운다 — 하나만 지우면 아래 putAll 이 되살린다.
        List<RunTransfer> outgoingTransfers = runTransferRepository.findAllByFromRunIdAndAcademyId(run.getId(),
                run.getAcademyId());
        Map<Long, Long> removedStops = new HashMap<>();
        for (RunTransfer transfer : outgoingTransfers) {
            Long stopId = stopOverrides.getOrDefault(transfer.getStudentId(), studentStops.get(transfer.getStudentId()));
            if (stopId != null) {
                removedStops.put(transfer.getStudentId(), stopId);
            }
            studentIds.remove(transfer.getStudentId());
            studentStops.remove(transfer.getStudentId());
            stopOverrides.remove(transfer.getStudentId());
        }
        List<RunTransfer> incomingTransfers = runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(),
                run.getAcademyId());
        for (RunTransfer transfer : incomingTransfers) {
            if (!studentIds.contains(transfer.getStudentId())) {
                studentIds.add(transfer.getStudentId());
            }
            stopOverrides.put(transfer.getStudentId(), transfer.getStopId());
        }

        studentStops.putAll(stopOverrides);
        // 강제 추가·도착 이동으로 다시 태운 학생은 absent 가 아니다 — 한 학생에 행 하나(uk_run_rider_run_student).
        absentStops.keySet().removeAll(studentStops.keySet());
        absentStops.keySet().removeAll(removedStops.keySet());
        Set<Long> addedStudentIds = new HashSet<>(studentIds);
        addedStudentIds.removeAll(weeklyStudentIds);
        return new ProjectedRoster(studentIds, stopOverrides, studentStops, absentStops, addedStudentIds, removedStops,
                incomingTransfers, forcedAdditions.size() + outgoingTransfers.size() + incomingTransfers.size());
    }

    /**
     * 그 회차의 강제 추가·이동 대기 행 수 — 확정 저장이 트랜잭션 안에서 다시 세어 {@link ProjectedRoster#stagedRowCount()}
     * 와 다르면 계산 도중 새 행이 들어온 것이다(BR-044). 대기 행은 지워지지 않으므로 수만 비교해도 충분하다.
     */
    public int stagedRowCount(Run run) {
        return (int) runForcedAdditionRepository.countByRunIdAndAcademyId(run.getId(), run.getAcademyId())
                + runTransferRepository.findAllByFromRunIdAndAcademyId(run.getId(), run.getAcademyId()).size()
                + runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(), run.getAcademyId()).size();
    }

    /** 그 날짜의 요일 — {@code route.weekday} 의 값 공간으로 옮긴다. 시계를 보지 않는다. */
    private static Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
