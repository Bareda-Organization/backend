package src.backend.student.access;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.rider.RunRiderReader;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.stops.RouteStopReader;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.lookup.RunLookup;
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
 *
 * <p>{@code run}·{@code routing}·{@code boarding} 의 저장소를 직접 참조하지 않고 각 모듈이 내놓은
 * 읽기 전용 진입점({@link RunLookup}·{@link RouteStopReader}·{@link RunRiderReader})만 부른다
 * (BR-094 · BR-162, ARCHITECTURE §3.3 읽기 방향).
 */
@Component
@RequiredArgsConstructor
public class StudentRunResolver {

    private final RunLookup runLookup;

    private final RunRiderReader runRiderReader;

    private final RouteStopReader routeStopReader;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final Clock clock;

    /** {@code run_id} 를 명시한 경우(§3.10) — 학원 밖이거나 그 학생 회차가 아니면 둘 다 404. */
    public Run resolveByRunId(Long academyId, Long studentId, Long runId) {
        Run run = runLookup.findByIdAndAcademyId(runId, academyId)
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
     * 그날 이 학생이 속한 회차 전부(§3.5, P-04 · S-01) — {@link #resolveByDate} 와 달리 1건으로
     * 좁히지 않는다. 등원·하원처럼 하루에 여러 회차를 가질 수 있어 목록 화면은 전부를 보여줘야 한다.
     * 취소된 회차를 빼고 출발 시각 순으로 정렬하는 것은 {@link #mostRelevant} 의 후보 산출과 같다 —
     * {@link #belongsTo} 를 그대로 재사용해 idle·확정 이후 판정 로직을 중복시키지 않는다.
     */
    public List<Run> resolveAllByDate(Long academyId, Long studentId, LocalDate date) {
        return runsOf(academyId, studentId, date);
    }

    /**
     * 관련도 순서 — ①운행 중(MOVING)인 것 ②아직 출발 전 중 가장 이른 것 ③이미 지난 것 중 가장 최근
     * 것. 한 학생이 하루에 등원·하원 두 회차를 갖는 경우를 겨냥한 순서다(판단 근거 — 보고서에 근거를
     * 남긴다).
     */
    private Optional<Run> mostRelevant(Long academyId, Long studentId, LocalDate date) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Run> candidates = runsOf(academyId, studentId, date);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().filter(run -> run.getStatus() == RunStatus.MOVING).findFirst()
                .or(() -> candidates.stream().filter(run -> run.getDepartTime().isAfter(now))
                        .min(Comparator.comparing(Run::getDepartTime)))
                .or(() -> candidates.stream().max(Comparator.comparing(Run::getDepartTime)));
    }

    /**
     * 그날 이 학생이 속한 취소되지 않은 회차 — {@link #belongsTo} 와 같은 판정을 회차마다 묻지 않고 한 번에
     * 모은다(BR-058). 확정 이후는 명단 1질의, 확정 전은 방향마다 요일별 주소 1질의 + 그 승하차지를 편성에 둔
     * 차량 1질의라 학원의 그날 회차 수와 무관하다.
     */
    private List<Run> runsOf(Long academyId, Long studentId, LocalDate date) {
        List<Run> runs = runLookup.findAllByAcademyIdAndServiceDate(academyId, date)
                .stream()
                .filter(run -> !run.isCanceled())
                .toList();
        List<Long> confirmedRunIds = runs.stream().filter(run -> run.getStatus() != RunStatus.IDLE)
                .map(Run::getId).toList();
        Set<Long> riderRunIds = confirmedRunIds.isEmpty() ? Set.of()
                : runRiderReader.runIdsExcludingRemoved(academyId, studentId, confirmedRunIds);
        Map<Direction, Set<Long>> idleBusIds = new EnumMap<>(Direction.class);
        runs.stream().filter(run -> run.getStatus() == RunStatus.IDLE).map(Run::getDirection).distinct()
                .forEach(direction -> idleBusIds.put(direction,
                        busesServing(academyId, studentId, weekdayOf(date), direction)));
        return runs.stream()
                .filter(run -> run.getStatus() == RunStatus.IDLE
                        ? idleBusIds.get(run.getDirection()).contains(run.getBusId())
                        : riderRunIds.contains(run.getId()))
                .toList();
    }

    /** 그 요일·방향에 이 학생의 승하차지를 고정 노선에 둔 차량들 — {@link #matchesFixedRoute} 를 차량 묶음으로 푼 것. */
    private Set<Long> busesServing(Long academyId, Long studentId, Weekday weekday, Direction direction) {
        return weeklyAddressRepository.findDailyStops(academyId, List.of(studentId), weekday, direction).stream()
                .findFirst()
                .map(stop -> routeStopReader.busIdsServingStop(academyId, weekday, direction, stop.getStopId()))
                .orElse(Set.of());
    }

    /**
     * 이 학생이 그 회차의 대상인가 — 확정 전은 고정 노선, 확정 이후는 명단({@code run_rider})으로 판정한다.
     * 탑승 의사 OFF 로 남은 {@code absent} 행도 대상이다(①에서 끈 학생의 ② 켜기는 404 가 아니라 403) · 다른
     * 버스로 옮긴 {@code removed} 행은 대상이 아니다(BR-016).
     */
    public boolean belongsTo(Run run, Long studentId) {
        if (run.getStatus() == RunStatus.IDLE) {
            return matchesFixedRoute(run, studentId);
        }
        // 버스 간 이동으로 빠진 회차(change=removed)는 그 학생의 회차가 아니다(BR-016) — 명단에 남는 것은 표시용이다.
        return runRiderReader.findRider(run.getId(), studentId)
                .filter(rider -> rider.change() != ChangeType.REMOVED)
                .isPresent();
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
        return routeStopReader.fixedRouteStops(run.getAcademyId(), run.getBusId(), weekday, run.getDirection())
                .stream().map(RouteStopReader.Entry::stopId).anyMatch(studentStopId::equals);
    }

    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
