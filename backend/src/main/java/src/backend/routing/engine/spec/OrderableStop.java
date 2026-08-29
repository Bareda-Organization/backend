package src.backend.routing.engine.spec;

import java.util.Objects;

import src.backend.routing.domain.GeoPoint;

/**
 * 재배열 대상 정차지 하나.
 *
 * <p>{@code riderCount} 를 함께 싣는 이유는 정원·체류시간 판정의 입력이기 때문이다 — 좌표만으로는
 * "한 학생을 오래 태우는 해" 와 "여럿을 짧게 태우는 해" 를 가릴 수 없다(TECH_DECISIONS §8.5.2).
 */
public record OrderableStop(long stopId, GeoPoint point, int riderCount) {

    public OrderableStop {
        Objects.requireNonNull(point, "정차지 좌표가 없다");
        if (riderCount < 0) {
            throw new IllegalArgumentException("탑승 인원은 음수일 수 없다: " + riderCount);
        }
    }
}
