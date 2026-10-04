package src.backend.student.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.rider.RunRiderReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.stops.RouteStopReader;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.access.StudentRunsAccess;
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
 * 정차지가 <b>하나뿐</b>이다(등원은 승차지, 하원은 하차지) — 반대쪽 끝은 항상 학원이고, 학원은
 * {@code run_stop}/{@code route_stop} 행이 아니라서(ERD.md) {@code windowed} 파이프라인만으로는
 * 나오지 않는다. <b>Ruling 288</b> — 등원(TO_ACADEMY)의 하차지·하원(FROM_ACADEMY)의 승차지가 곧
 * 학원이므로, {@link Academy} 엔티티에서 합성한 항목 하나를 방향에 따라 뒤(등원)/앞(하원)에 덧붙인다
 * (좌표·이름은 {@code Academy}, {@code stop_id} 는 API_SPEC §1.13 의 "경유 지점은 항상 null" 선례를
 * 따라 {@code null}).
 *
 * <p>고정 노선·확정 노선의 정차지 순서, 명단 소속은 {@code routing}·{@code boarding} 저장소를 직접
 * 참조하지 않고 각 모듈의 읽기 전용 진입점({@link RouteStopReader}·{@link RunRiderReader})만 부른다
 * (BR-094, ARCHITECTURE §3.3 읽기 방향).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentRouteQueryService {

    private final StudentRunsAccess studentRunsAccess;

    private final StudentRunResolver studentRunResolver;

    private final RunRiderReader runRiderReader;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final RouteStopReader routeStopReader;

    private final StopRepository stopRepository;

    private final BusRepository busRepository;

    private final AssignmentRepository assignmentRepository;

    private final ManagerRepository managerRepository;

    private final AcademyRepository academyRepository;

    private final Clock clock;

    /** 학생 1명의 노선(§3.10) — 오늘 회차가 없으면 {@code 404 RUN_NOT_FOUND}, 학생·회차 접근 불가는 각각의 사양 오류. */
    public StudentRouteResponse route(AuthUser requester, Long studentId, String rawDate, String rawRunId) {
        Student student = studentRunsAccess.resolve(requester, studentId);
        Run run = resolveRun(student, rawDate, rawRunId);
        Long academyId = student.getAcademyId();

        Long myStopId = myStopId(run, student.getId());
        List<RouteStopReader.Entry> windowed = window(stopEntries(run, academyId), myStopId);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(academyId, windowed.stream().map(RouteStopReader.Entry::stopId).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        List<StudentRouteResponse.Stop> stops = new ArrayList<>(windowed.stream()
                .map(entry -> toStop(entry, stopsById.get(entry.stopId())))
                .toList());
        addAcademyStop(stops, academyId, run.getDirection());

        String busNo = busRepository.findByIdAndAcademyId(run.getBusId(), academyId).map(Bus::getBusNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        RouteStopReader.RoadPath stored = storedRoadPath(run);

        return new StudentRouteResponse(run.getId(), busNo, run.getDepartTime(), run.getStatus() != RunStatus.IDLE,
                contactOf(run, ManagerRole.DRIVER), escortContactOf(run), myStopId, stops,
                RoadPathSlicer.slice(stored.points(), stops), stored.fallbackUsed());
    }

    /** 확정 전(idle)은 도로 경로가 아직 없어 빈 값, 확정 후는 현재 버전에 저장된 좌표(외부 지도 API 는 부르지 않는다). */
    private RouteStopReader.RoadPath storedRoadPath(Run run) {
        return run.getStatus() == RunStatus.IDLE ? RouteStopReader.RoadPath.EMPTY
                : routeStopReader.confirmedRoadPath(run.getId());
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
            Weekday weekday = Weekday.of(run.getServiceDate());
            return weeklyAddressRepository
                    .findDailyStops(run.getAcademyId(), List.of(studentId), weekday, run.getDirection()).stream()
                    .findFirst().map(StudentDailyStop::getStopId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        }
        return runRiderReader.findRider(run.getId(), studentId).map(RunRiderReader.Entry::stopId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    /** 확정 전은 고정 노선, 확정 후는 확정 노선(현재 버전)의 정차지 순서를 그대로 받는다(routing 모듈 진입점). */
    private List<RouteStopReader.Entry> stopEntries(Run run, Long academyId) {
        if (run.getStatus() == RunStatus.IDLE) {
            return routeStopReader.fixedRouteStops(academyId, run.getBusId(), Weekday.of(run.getServiceDate()),
                    run.getDirection());
        }
        return routeStopReader.confirmedRouteStops(run.getId(), academyId);
    }

    /** 이 학생 정차지의 위치를 기준으로 그 앞 최대 2개까지만 남긴다(§3.10 "표시 범위"). */
    private List<RouteStopReader.Entry> window(List<RouteStopReader.Entry> entries, Long myStopId) {
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

    private StudentRouteResponse.Stop toStop(RouteStopReader.Entry entry, Stop stop) {
        if (stop == null) {
            return new StudentRouteResponse.Stop(entry.stopId(), entry.seq(), null, null, null, null, entry.change(),
                    entry.arrivedAt());
        }
        return new StudentRouteResponse.Stop(stop.getId(), entry.seq(), stop.getName(), stop.getAddress(),
                stop.getLat(), stop.getLng(), entry.change(), entry.arrivedAt());
    }

    /**
     * 학원은 {@code run_stop}/{@code route_stop} 행이 없어 위 {@code windowed} 파이프라인에 없다
     * (Ruling 288). 등원(TO_ACADEMY)은 학원이 하차지라 뒤에, 하원(FROM_ACADEMY)은 학원이 승차지라
     * 앞에 붙인다. {@code stop_id} 는 API_SPEC §1.13 의 "경유 지점은 항상 null" 선례를 그대로 쓴다.
     */
    private void addAcademyStop(List<StudentRouteResponse.Stop> stops, Long academyId, Direction direction) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (direction == Direction.TO_ACADEMY) {
            int seq = stops.isEmpty() ? 1 : stops.get(stops.size() - 1).seq() + 1;
            stops.add(academyStop(academy, seq));
        } else {
            int seq = stops.isEmpty() ? 1 : stops.get(0).seq() - 1;
            stops.add(0, academyStop(academy, seq));
        }
    }

    private StudentRouteResponse.Stop academyStop(Academy academy, int seq) {
        return new StudentRouteResponse.Stop(null, seq, academy.getName(), academy.getAddress(), academy.getLat(),
                academy.getLng(), null, null);
    }

    /** 배치가 아직 없으면 이름·전화 전부 {@code null} — §3.10 {@code ◐}(배치가 있을 때만, BR-055). */
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

}
