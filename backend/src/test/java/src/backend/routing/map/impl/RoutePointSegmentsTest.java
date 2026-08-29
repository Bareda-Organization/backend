package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import src.backend.routing.domain.GeoPoint;

/**
 * 구간 분할의 <b>길이 불변식</b>을 지점 수 × 상한 조합으로 고정한다.
 *
 * <p>{@code NaverDirectionsResilienceTest} 는 실제 호출로 이 성질을 한 조합에서만 본다. 경계 지점의
 * 중복·누락은 상한의 배수 부근에서만 드러나는 형태라, 조합을 훑는 자리가 따로 필요하다 — 어긋나면
 * 총 거리가 한 구간만큼 줄거나 늘고, 결과 형태는 그대로라 눈으로 드러나지 않는다.
 */
class RoutePointSegmentsTest {

    /**
     * 구간별 구간 수의 합은 언제나 {@code points - 1} 이고, 어떤 구간도 상한을 넘지 않는다.
     *
     * <p>상한의 배수 언저리(7·8·13·14)를 고른 이유는 마지막 구간이 지점 1개만 남아 사라지는 사고가
     * 그 자리에서 나기 때문이다.
     */
    @ParameterizedTest
    @CsvSource({"2,7", "7,7", "8,7", "10,7", "13,7", "14,7", "20,7", "3,2", "10,2", "10,3"})
    void 구간별_구간_수의_합이_입력_구간_수와_같다(int pointCount, int maxPointsPerCall) {
        List<GeoPoint> points = 지점_여러개(pointCount);

        List<List<GeoPoint>> segments = RoutePointSegments.split(points, maxPointsPerCall);

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.size()).isBetween(2, maxPointsPerCall);
        });
        assertThat(segments.stream().mapToInt(segment -> segment.size() - 1).sum())
                .as("경계 지점이 중복됐거나 버려졌다")
                .isEqualTo(pointCount - 1);
    }

    /** 앞 구간의 마지막 지점이 다음 구간의 첫 지점이다 — 이어 붙일 때 경계가 비지 않는 근거다. */
    @Test
    void 이웃한_구간은_경계_지점을_공유한다() {
        List<List<GeoPoint>> segments = RoutePointSegments.split(지점_여러개(10), 7);

        assertThat(segments).hasSizeGreaterThan(1);
        for (int i = 0; i < segments.size() - 1; i++) {
            assertThat(segments.get(i).getLast()).isEqualTo(segments.get(i + 1).getFirst());
        }
    }

    /** 상한이 1이면 구간을 만들 수 없다 — 설정 오기를 무한 반복이 아니라 즉시 실패로 드러낸다. */
    @Test
    void 상한이_2_미만이면_거부한다() {
        assertThatThrownBy(() -> RoutePointSegments.split(지점_여러개(5), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<GeoPoint> 지점_여러개(int count) {
        List<GeoPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new GeoPoint(new BigDecimal("37.500000").add(new BigDecimal("0.001000").multiply(BigDecimal.valueOf(i))),
                    new BigDecimal("127.000000")));
        }
        return points;
    }
}
