package src.backend.student.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.dto.StudentRouteResponse;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 학부모 앱의 자녀 노선 조회(LOC-03, API_SPEC §3.10, 목표 12).
 *
 * <p>확정 전(idle)·확정 후 두 갈래를 갖는다 — idle 은 {@code run_stop}(확정 노선) 이 아직 없어
 * 고정 노선({@code route}·{@code route_stop})으로 대신 보여준다(§3.10 "미확정이어도 에러 아님").
 * 두 갈래 모두 최종적으로 같은 windowing 을 거친다.
 *
 * <p><b>판단 근거</b> — "승차지 이전 2개 · 승차지 · 하차지만"(§3.10) 을, 이 학생의 정차지 하나를
 * 중심으로 그 앞 최대 2개까지만 보여주는 단일 규칙으로 구현했다. 이 시스템의 편도 회차는 학생마다
 * 정차지가 <b>하나뿐</b>이다(등원은 승차지, 하원은 하차지 — 반대쪽 끝은 항상 학원이고 학원은
 * {@code stops[]} 항목이 아니다) — 그래서 "승차지" 규칙과 "하차지" 규칙이 이 구현에서는 같은
 * 코드로 수렴한다. 방향별로 분기해 별도 규칙을 둘 수도 있었으나 그 경우 명세가 실제로 요구하는
 * 차이가 무엇인지 근거가 없어 이 판단을 보고서에 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentRouteQueryService {

    private final LinkedChildLookup linkedChildLookup;

    private final StudentRunResolver studentRunResolver;

    private final RunRiderRepository runRiderRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final BusRepository busRepository;

    private final AssignmentRepository assignmentRepository;

    private final ManagerRepository managerRepository;

    private final Clock clock;

    public StudentRouteResponse route(AuthUser requester, Long studentId, String rawDate, String rawRunId) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        Run run = resolveRun(student, rawDate, rawRunId);
        Long academyId = student.getAcademyId();

        Long myStopId = myStopId(run, student.getId());
        List<WindowEntry> windowed = window(stopEntries(run, academyId), myStopId);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(academyId, windowed.stream().map(WindowEntry::stopId).toList()).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        List<StudentRouteResponse.Stop> stops = windowed.stream()
                .map(entry -> toStop(entry, stopsById.get(entry.stopId())))
                .toList();

        String busNo = busRepository.findByIdAndAcademyId(run.getBusId(), academyId).map(Bus::getBusNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        return new StudentRouteResponse(run.getId(), busNo, run.getDepartTime(), run.getStatus() != RunStatus.IDLE,
                contactOf(run, ManagerRole.DRIVER), escortContactOf(run), myStopId, stops);
    }

    /** {@code run_id} 가 있으면 그 회차만, 없으면 {@code date}(또는 오늘)로 가장 관련 있는 회차를 고른다. */
    private Run resolveRun(Student student, String rawDate, String rawRunId) {
        if (rawRunId != null) {
            return studentRunResolver.resolveByRunId(student.getAcademyId(), student.getId(), parseRunId(rawRunId));
        }
        LocalDate date = rawDate == null ? LocalDate.now(clock) : ApiValues.date(rawDate);
        return studentRunResolver.resolveByDate(student.getAcademyId(), student.getId(), date)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    private Long parseRunId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "run_id 는 숫자여야 합니다: " + raw);
        }
    }

    /** idle 은 명단이 없어 그날 고정 노선의 정차지로, 확정 후는 실제 명단({@code run_rider})으로 찾는다. */
    private Long myStopId(Run run, Long studentId) {
        if (run.getStatus() == RunStatus.IDLE) {
            Weekday weekday = weekdayOf(run.getServiceDate());
            return weeklyAddressRepository
                    .findDailyStops(run.getAcademyId(), List.of(studentId), weekday, run.getDirection()).stream()
                    .findFirst().map(StudentDailyStop::getStopId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        }
        return runRiderRepository.findByRunIdAndStudentId(run.getId(), studentId).map(RunRider::getStopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    private List<WindowEntry> stopEntries(Run run, Long academyId) {
        if (run.getStatus() == RunStatus.IDLE) {
            return routeRepository
                    .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(),
                            weekdayOf(run.getServiceDate()), run.getDirection())
                    .map(Route::getId)
                    .map(routeId -> routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeId, academyId))
                    .orElse(List.of())
                    .stream()
                    .map(routeStop -> new WindowEntry(routeStop.getStopId(), routeStop.getSeq(), null))
                    .toList();
        }
        Long currentVersionId = confirmedRouteRepository.findById(run.getId()).map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId).stream()
                .filter(runStop -> runStop.getStopId() != null)
                .map(runStop -> new WindowEntry(runStop.getStopId(), runStop.getSeq(),
                        runStop.getChange() == null ? null : runStop.getChange().name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** 이 학생 정차지의 위치를 기준으로 그 앞 최대 2개까지만 남긴다(§3.10 "표시 범위"). */
    private List<WindowEntry> window(List<WindowEntry> entries, Long myStopId) {
        int myIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (myStopId.equals(entries.get(i).stopId())) {
                myIndex = i;
                break;
            }
        }
        if (myIndex < 0) {
            return List.of();
        }
        return entries.subList(Math.max(0, myIndex - 2), myIndex + 1);
    }

    private StudentRouteResponse.Stop toStop(WindowEntry entry, Stop stop) {
        if (stop == null) {
            return new StudentRouteResponse.Stop(entry.stopId(), entry.seq(), null, null, null, null, entry.change());
        }
        return new StudentRouteResponse.Stop(stop.getId(), entry.seq(), stop.getName(), stop.getAddress(),
                stop.getLat(), stop.getLng(), entry.change());
    }

    /** 배치가 아직 없으면 이름·전화 전부 {@code null} — §3.10 에 이 경우의 에러 코드가 없어 그대로 비운다. */
    private StudentRouteResponse.Contact contactOf(Run run, ManagerRole role) {
        return assignmentRepository.findByRunIdAndRole(run.getId(), role)
                .flatMap(assignment -> managerRepository.findById(assignment.getManagerId()))
                .map(manager -> new StudentRouteResponse.Contact(manager.getName()))
                .orElse(new StudentRouteResponse.Contact(null));
    }

    private StudentRouteResponse.EscortContact escortContactOf(Run run) {
        return assignmentRepository.findByRunIdAndRole(run.getId(), ManagerRole.ESCORT)
                .flatMap(assignment -> managerRepository.findById(assignment.getManagerId()))
                .map(manager -> new StudentRouteResponse.EscortContact(manager.getName(), manager.getPhone()))
                .orElse(new StudentRouteResponse.EscortContact(null, null));
    }

    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    private record WindowEntry(Long stopId, int seq, String change) {
    }
}
