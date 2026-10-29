package src.backend.run.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 회차의 정차 행에 딸린 승하차지·경유 지점·학원·예상 탑승 인원을 조회해 {@link RunRouteStopLookup} 으로 묶는다.
 * 호출부({@link RunRouteQueryService})의 읽기 전용 트랜잭션 안에서 부른다.
 *
 * <p>학원 조건은 요청자가 아니라 <b>회차의 학원</b>으로 건다(BR-014) — 메인 관리자는 {@code academyId}
 * 가 {@code null} 이라 요청자 기준이면 네 조회가 전부 0행이 된다.
 */
@Component
@RequiredArgsConstructor
class RunRouteStopLookupLoader {

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final AcademyRepository academyRepository;

    /** 승하차지 → 경유 지점 → 탑승 인원 → 학원 순으로 조회한다. */
    RunRouteStopLookup load(Run run, List<RunStop> runStops) {
        Long academyId = run.getAcademyId();
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(academyId,
                        runStops.stream().map(RunStop::getStopId).filter(id -> id != null).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, Waypoint> waypointsById = waypointRepository.findAllAppliedByRunIdAndAcademyId(run.getId(),
                academyId).stream().collect(Collectors.toMap(Waypoint::getId, waypoint -> waypoint));
        Map<Long, Long> studentCountsByStopId = studentCountsByStopId(run);
        Academy academy = academyRepository.findById(academyId).orElse(null);
        return new RunRouteStopLookup(stopsById, waypointsById, academy, studentCountsByStopId);
    }

    /** 승하차지별 예상 탑승 인원 — {@code absent} 는 오늘 자체가 등원 대상이 아니라 뺀다(로스터와 같은 근거). */
    private Map<Long, Long> studentCountsByStopId(Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        return riders.stream()
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .collect(Collectors.groupingBy(RunRider::getStopId, Collectors.counting()));
    }
}
