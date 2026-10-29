package src.backend.student.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.student.access.RouteStopSource;
import src.backend.student.dto.StudentRouteResponse;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 자녀 노선 응답(API_SPEC §3.10)의 정차지 목록을 만든다 — 이 학생 정차지 앞 최대 2개로 줄이고(표시 범위), 승하차지 마스터에서 이름 · 좌표를 붙이고, 학원
 * 정차지를 방향에 맞는 쪽 끝에 덧붙인다. {@link StudentRouteQueryService} 의 읽기 전용 트랜잭션 안에서 불리는 평범한 빈이다.
 */
@Component
@RequiredArgsConstructor
class RouteStopAssembler {

    private final StopRepository stopRepository;

    private final AcademyRepository academyRepository;

    /** 정차지 순서 전체에서 표시 범위를 잘라 응답 정차지로 옮기고 학원 정차지를 붙인다. */
    List<StudentRouteResponse.Stop> assemble(Long academyId, List<RouteStopSource.Entry> entries, Long myStopId,
            Direction direction) {
        List<RouteStopSource.Entry> windowed = window(entries, myStopId);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(academyId, windowed.stream().map(RouteStopSource.Entry::stopId).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        List<StudentRouteResponse.Stop> stops = new ArrayList<>(windowed.stream()
                .map(entry -> toStop(entry, stopsById.get(entry.stopId()), myStopId))
                .toList());
        addAcademyStop(stops, academyId, direction);
        return stops;
    }

    /** 이 학생 정차지의 위치를 기준으로 그 앞 최대 2개까지만 남긴다(§3.10 "표시 범위"). */
    private static List<RouteStopSource.Entry> window(List<RouteStopSource.Entry> entries, Long myStopId) {
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

    /**
     * 주소 원문은 이 학생 본인의 승하차지에만 싣는다(Ruling 853) — 앞선 승하차지는 다른 아이의 집 주소에서 만들어졌을
     * 수 있어 학부모의 주소 원문 조회 불가(FEATURE_SPEC §6.1·§6.3)와 맞춘다. 이름 · 좌표 · 순번은 지도 선과 번호 핀의
     * 재료라 그대로 둔다.
     */
    private static StudentRouteResponse.Stop toStop(RouteStopSource.Entry entry, Stop stop, Long myStopId) {
        if (stop == null) {
            return new StudentRouteResponse.Stop(entry.stopId(), entry.seq(), null, null, null, null, entry.change(),
                    entry.arrivedAt());
        }
        String address = stop.getId().equals(myStopId) ? stop.getAddress() : null;
        return new StudentRouteResponse.Stop(stop.getId(), entry.seq(), stop.getName(), address,
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

    private static StudentRouteResponse.Stop academyStop(Academy academy, int seq) {
        return new StudentRouteResponse.Stop(null, seq, academy.getName(), academy.getAddress(), academy.getLat(),
                academy.getLng(), null, null);
    }
}
