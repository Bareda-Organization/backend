package src.backend.routing.map.spec;

import java.util.List;

import src.backend.routing.domain.GeoPoint;

/**
 * 이웃한 두 지점 사이의 한 구간 — ETA 산출(④단계)이 이 값을 누적한다.
 *
 * @param distanceMeters  구간 거리(m). 폴백일 때는 직선거리다
 * @param durationSeconds 구간 소요 시간(초). 폴백일 때는 평균 속도 가정으로 환산한 값이다
 * @param path            이 구간이 지나는 도로 좌표(순서 있음, Ruling 309 · R15 T1). 공급자는 세그먼트
 *                         단위로만 좌표를 주므로(경유지별 값 미제공) <b>세그먼트의 첫 leg 에만</b> 채워지고
 *                         나머지 leg 는 빈 목록일 수 있다 — {@link RoadRoute#roadPath()} 가 전부 이어
 *                         붙이면서 이음매 중복을 걷어낸다
 */
public record RoadLeg(int distanceMeters, int durationSeconds, List<GeoPoint> path) {

    /** 방어적 복사 — 호출자가 넘긴 목록을 나중에 고쳐도 이 값이 안 바뀐다({@link RoadRoute}와 같은 근거). */
    public RoadLeg {
        path = List.copyOf(path);
    }
}
