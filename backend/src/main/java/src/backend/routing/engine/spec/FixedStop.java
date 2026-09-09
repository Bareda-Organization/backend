package src.backend.routing.engine.spec;

import java.util.Objects;

import src.backend.routing.domain.GeoPoint;

/**
 * 위치가 고정된 정차지 — 경유 지점(RTE-10)이 이 형태로 들어온다.
 *
 * <p>{@code seq} 는 후보 순번이 아니라 <b>최종 순번</b>이다. 관리자가 지정한 순번을 최적화가
 * 뒤집으면 지정의 의미가 소멸하므로(ARCHITECTURE §8.2), 엔진은 이 자리를 비워 두고 나머지만 채운다.
 */
public record FixedStop(long waypointId, GeoPoint point, int seq) {

    public FixedStop {
        Objects.requireNonNull(point, "경유 지점 좌표가 없다");
        if (seq < 1) {
            throw new IllegalArgumentException("순번은 1부터 시작한다: " + seq);
        }
    }
}
