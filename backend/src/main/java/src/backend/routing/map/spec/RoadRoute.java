package src.backend.routing.map.spec;

import java.util.ArrayList;
import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 지점열 전체의 도로 경로 — {@code legs.size() == points.size() - 1} 이 <b>폴백에서도</b> 성립한다.
 *
 * <p>길이 불변식을 폴백까지 끌고 가는 이유는 호출부가 <b>형태로 분기하지 않게</b> 하기 위함이다.
 * 폴백일 때만 구간 수가 달라지면 ETA 산출이 "구간이 몇 개인가" 를 매번 다시 세게 되고, 그 분기가
 * 빠진 자리에서 마지막 승하차지의 도착 시각이 조용히 사라진다.
 *
 * @param legs         구간 목록. 입력 지점열과 같은 순서다
 * @param fallbackUsed {@code true} 면 직선거리 근사다({@code TECH_DECISIONS §8}) — 이 값이 실리지
 *                     않으면 근사값이 실측값과 구별되지 않는다
 */
public record RoadRoute(List<RoadLeg> legs, boolean fallbackUsed) {

    /** 방어적 복사를 컴팩트 생성자에 두어, 호출자가 넘긴 목록을 나중에 고쳐도 결과가 안 바뀐다. */
    public RoadRoute {
        legs = List.copyOf(legs);
    }

    /**
     * 전체 지점열의 도로 좌표를 하나로 잇는다(Ruling 309 · R15 T1) — {@code route_version.road_path}
     * 와 §5.19 응답 {@code road_path} 가 이 값을 그대로 쓴다.
     *
     * <p><b>구간 경계에서 좌표가 겹치는 것을 여기서 걷어낸다.</b> {@link RoadLeg#path()} 는 세그먼트
     * 단위로만 채워지므로(경유지별 값을 공급자가 안 준다), 이웃한 두 세그먼트의 첫·끝 좌표가 같은
     * 지점을 가리킨다 — 그대로 이으면 지도에 그 지점이 두 번 찍혀 경로가 겹쳐 보인다.
     */
    public List<GeoPoint> roadPath() {
        List<GeoPoint> path = new ArrayList<>();
        for (RoadLeg leg : legs) {
            for (GeoPoint point : leg.path()) {
                if (path.isEmpty() || !path.get(path.size() - 1).equals(point)) {
                    path.add(point);
                }
            }
        }
        return List.copyOf(path);
    }
}
