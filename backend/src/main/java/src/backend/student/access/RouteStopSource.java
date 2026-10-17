package src.backend.student.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.run.entity.Run;
import src.backend.student.dto.RoadPoint;

/**
 * 학생 모듈의 회차·노선 판정이 읽는 정차지·도로 좌표 포트(BR-094 · BR-162, AR-4) — {@code routing} 이 구현하고 학생 모듈은 이 인터페이스만
 * 안다. 허용 방향이 {@code routing → student} 라(ARCHITECTURE §3.3) 포트를 학생 쪽에 둔다.
 */
public interface RouteStopSource {

    /** 그 요일·방향의 고정 노선 정차지를 순서대로 — 편성이 없으면 빈 목록(§3.10 "미확정이어도 에러 아님"). */
    List<Entry> fixedRouteStops(Long academyId, Long busId, Weekday weekday, Direction direction);

    /** 확정 노선(현재 버전)의 정차지를 순서대로 — 아직 확정되지 않았으면 빈 목록. */
    List<Entry> confirmedRouteStops(Long runId, Long academyId);

    /** 확정 노선(현재 버전)에 저장된 도로 좌표와 폴백 여부 — 외부 지도 API 를 부르지 않고 저장값만 읽는다. */
    RoadPath confirmedRoadPath(Long runId);

    /** 그 회차 확정 노선의 그 승하차지를 이미 출발 처리했는지(Ruling 871). */
    boolean isStopDeparted(Long runId, Long stopId);

    /** 그 요일·방향에 그 정차지를 고정 노선에 둔 차량 id 전부 — 확정 전(idle) 회차의 소속 판정(BR-058)에 쓰인다. */
    Set<Long> busIdsServingStop(Long academyId, Weekday weekday, Direction direction, Long stopId);

    /** 마지막으로 도착 처리된 정차지의 도착 시각(§4.3 {@code current_stop} 과 같은 판정) — 도착 기록이 없으면 {@code null}. */
    OffsetDateTime lastArrivedAt(Run run);

    /**
     * 정차지 · 순번 · 확정 후 변경 구분(있으면) · 도착 처리 시각(있으면) — 고정 노선 항목은 {@code change}·{@code arrivedAt} 이 항상
     * {@code null}(아직 달리지 않은 노선이다). 확정 노선에서도 지나가지 않은 곳은 {@code arrivedAt} 이 {@code null} 이다.
     */
    record Entry(Long stopId, int seq, String change, OffsetDateTime arrivedAt) {
    }

    /** 확정 노선 버전의 도로 좌표(순서 있음)와 직선 근사 여부({@code fallback_used}) — 확정 전은 {@link #EMPTY}. */
    record RoadPath(List<RoadPoint> points, boolean fallbackUsed) {

        public static final RoadPath EMPTY = new RoadPath(List.of(), false);
    }
}
