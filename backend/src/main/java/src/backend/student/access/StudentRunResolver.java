package src.backend.student.access;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 학부모/학생 조회(LOC-02·LOC-03) 두 화면이 공유하는 "이 학생의 이 회차" 판정 — §3.10·§3.11 이
 * 학생 명단(§4.2)과 달리 <b>회차 하나를 먼저 특정</b>한 뒤에야 위치·노선을 답할 수 있어 별도로 둔다.
 *
 * <p>소속 판정이 회차 상태에 따라 갈린다. {@link RunStatus#IDLE} 은 아직 확정 배치 전이라
 * {@code run_rider} 명단이 없다(RTE-08 이 확정 시점에야 만든다) — 그래서 idle 회차는 그 학생의
 * 그날 고정 노선 배정(요일별 주소 → 편성 정차지)으로 대신 판정한다. 확정 이후(§CONFIRMED·MOVING·
 * FINISHED)는 실제 명단 {@code run_rider} 로 판정한다 — 명단이 확정 순간의 스냅샷이라 고정 노선이
 * 그 뒤 바뀌어도 이 판정은 흔들리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StudentRunResolver {

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final Clock clock;

    /** {@code run_id} 를 명시한 경우(§3.10) — 학원 밖이거나 그 학생 회차가 아니면 둘 다 404. */
    public Run resolveByRunId(Long academyId, Long studentId, Long runId) {
        Run run = runRepository.findByIdAndAcademyId(runId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (!belongsTo(run, studentId)) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    /** {@code date} 를 명시한 경우(§3.10) — 그날 그 학생 회차 중 가장 관련 있는 1건을 고른다. */
    public Optional<Run> resolveByDate(Long academyId, Long studentId, LocalDate date) {
        return mostRelevant(academyId, studentId, date);
    }

    /** 파라미터가 전혀 없는 경우(§3.11 — 쿼리 파라미터 자체가 없다) — 오늘 날짜로 고정한다. */
    public Optional<Run> resolveForToday(Long academyId, Long studentId) {
        return mostRelevant(academyId, studentId, LocalDate.now(clock));
    }

    /**
     * 관련도 순서 — ①운행 중(MOVING)인 것 ②아직 출발 전 중 가장 이른 것 ③이미 지난 것 중 가장 최근
     * 것. 한 학생이 하루에 등원·하원 두 회차를 갖는 경우를 겨냥한 순서다(판단 근거 — 보고서에 근거를
     * 남긴다).
     */
    private Optional<Run> mostRelevant(Long academyId, Long studentId, LocalDate date) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Run> candidates = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(academyId, date)
                .stream()
                .filter(run -> !run.isCanceled())
                .filter(run -> belongsTo(run, studentId))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().filter(run -> run.getStatus() == RunStatus.MOVING).findFirst()
                .or(() -> candidates.stream().filter(run -> run.getDepartTime().isAfter(now))
                        .min(Comparator.comparing(Run::getDepartTime)))
                .or(() -> candidates.stream().max(Comparator.comparing(Run::getDepartTime)));
    }

    private boolean belongsTo(Run run, Long studentId) {
        if (run.getStatus() == RunStatus.IDLE) {
            return matchesFixedRoute(run, studentId);
        }
        return runRiderRepository.findByRunIdAndStudentId(run.getId(), studentId).isPresent();
    }

    /** 확정 전 회차는 명단이 없어, 그날 고정 노선에 이 학생의 정차지가 실려 있는지로 대신 판정한다. */
    private boolean matchesFixedRoute(Run run, Long studentId) {
        Weekday weekday = weekdayOf(run.getServiceDate());
        List<StudentDailyStop> dailyStops = weeklyAddressRepository.findDailyStops(run.getAcademyId(),
                List.of(studentId), weekday, run.getDirection());
        if (dailyStops.isEmpty()) {
            return false;
        }
        Long studentStopId = dailyStops.get(0).getStopId();
        return routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .map(Route::getId)
                .map(routeId -> routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeId, run.getAcademyId()))
                .map(routeStops -> routeStops.stream().map(RouteStop::getStopId).anyMatch(studentStopId::equals))
                .orElse(false);
    }

    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
