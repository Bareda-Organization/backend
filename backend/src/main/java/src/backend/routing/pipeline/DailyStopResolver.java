package src.backend.routing.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 노선 계산 ①단계 — 그날의 승하차지를 좌표로 바꾸고 얻지 못한 학생을 분리한다
 * ({@code ARCHITECTURE §8.2} ①).
 *
 * <p><b>여기서 근접 병합을 다시 하지 않는다.</b> 같은 자리에 서는 주소를 하나로 묶는 일은 학생
 * 등록·주소 수정 시점에 이미 끝나 {@code weekly_address.stop_id} 로 굳어 있다(STU-05). 계산 시점에
 * 좌표를 다시 재서 묶으면 승하차지를 만드는 주체가 두 곳이 되고, 명단·미경유 판정이 어느 단위를
 * 가리키는지 갈린다. 그래서 이 클래스는 <b>거리를 재지 않고 참조를 따라간다</b>.
 */
@Component
public class DailyStopResolver {

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final StopRepository stopRepository;

    public DailyStopResolver(WeeklyAddressRepository weeklyAddressRepository, StopRepository stopRepository) {
        this.weeklyAddressRepository = weeklyAddressRepository;
        this.stopRepository = stopRepository;
    }

    /**
     * 명단을 정차지와 분리 학생으로 가른다 — 승하차지를 못 찾은 학생은 <b>예외가 아니라 목록</b>이다.
     *
     * <p>읽기 트랜잭션을 이 메서드에만 두는 것이 요점이다. 파이프라인 전체를 트랜잭션으로 감싸면
     * 외부 지도 API 호출(③단계)이 그 안에 들어가, 공급자가 느린 만큼 DB 커넥션을 붙든 채 대기한다
     * (횡단 규칙 16 이 주소 수정 트랜잭션에 대해 지목한 것과 같은 형태).
     */
    @Transactional(readOnly = true)
    public DailyStopResolution resolve(DailyRoster roster) {
        Map<Long, Long> studentToStop = studentToStop(roster);
        Map<Long, GeoPoint> pointsByStop = pointsByStop(roster.academyId(), studentToStop.values());

        Map<Long, Integer> ridersByStop = new TreeMap<>();
        List<Long> unresolved = new ArrayList<>();
        for (Long studentId : roster.studentIds()) {
            Long stopId = studentToStop.get(studentId);
            if (stopId == null || !pointsByStop.containsKey(stopId)) {
                unresolved.add(studentId);
                continue;
            }
            ridersByStop.merge(stopId, 1, Integer::sum);
        }
        return new DailyStopResolution(orderableStops(ridersByStop, pointsByStop), unresolved);
    }

    /**
     * 학생별 그날의 승하차지 — 일일 변경(P-06)이 요일별 주소(P-05)를 <b>덮는다</b>.
     *
     * <p>우선순위가 뒤집히면 그날만 바꾼 주소가 무시되어, 학부모가 신청하고 관리자가 승인한 변경이
     * 화면에는 반영된 것으로 보이면서 버스는 옛 자리에 선다.
     */
    private Map<Long, Long> studentToStop(DailyRoster roster) {
        Map<Long, Long> resolved = new LinkedHashMap<>();
        if (!roster.studentIds().isEmpty()) {
            for (StudentDailyStop row : weeklyAddressRepository.findDailyStops(
                    roster.academyId(), roster.studentIds(), roster.weekday(), roster.direction())) {
                resolved.put(row.getStudentId(), row.getStopId());
            }
        }
        resolved.putAll(roster.stopOverrides());
        return resolved;
    }

    private Map<Long, GeoPoint> pointsByStop(long academyId, Collection<Long> stopIds) {
        Map<Long, GeoPoint> points = new LinkedHashMap<>();
        if (stopIds.isEmpty()) {
            return points;
        }
        for (Stop stop : stopRepository.findAllByIdInAndAcademyId(stopIds, academyId)) {
            points.put(stop.getId(), new GeoPoint(stop.getLat(), stop.getLng()));
        }
        return points;
    }

    /**
     * 승하차지 번호 오름차순으로 세운다 — 순서가 명단 순서를 따라가면 <b>같은 명단을 다르게 정렬해
     * 넘겼을 때 산출 순서가 달라져</b>, 품질 회귀 판정(TECH_DECISIONS §8.5.2)이 무엇을 잰 값인지
     * 알 수 없어진다. 엔진의 동점 처리 기준({@code tieBreak: stop_id_asc})과 같은 축이다.
     */
    private static List<OrderableStop> orderableStops(Map<Long, Integer> ridersByStop,
            Map<Long, GeoPoint> pointsByStop) {
        List<OrderableStop> stops = new ArrayList<>(ridersByStop.size());
        ridersByStop.forEach((stopId, riders) -> stops.add(
                new OrderableStop(stopId, pointsByStop.get(stopId), riders)));
        return stops;
    }
}
