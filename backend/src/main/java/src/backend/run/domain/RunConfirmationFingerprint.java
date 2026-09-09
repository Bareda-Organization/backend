package src.backend.run.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.FixedStop;

/**
 * {@code route_version.input_fingerprint} 산출 — 같은 입력에서 나온 버전인지를 재계산 없이
 * 대조하기 위한 값이다(ERD §3.3, {@code varchar(64)}).
 *
 * <p>SHA-256 16진 표현이 정확히 64자라 컬럼 길이와 맞아떨어진다 — 다른 다이제스트를 쓰면 컬럼
 * 정의부터 다시 골라야 한다.
 *
 * <p><b>정렬해서 넣는 이유</b> — 학생 명단·경유 지점 목록은 조회·순회 순서가 실행마다 달라질 수
 * 있는데, 같은 집합이 다른 순서로 들어와 다른 지문이 나오면 "입력이 같다" 는 판정 자체가 성립하지
 * 않는다. 순서에 의미가 있는 값(요일·방향·출발 시각·좌표)은 그대로 두고, <b>집합 성격의 값만</b>
 * 정렬한다.
 */
public final class RunConfirmationFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private RunConfirmationFingerprint() {
    }

    /**
     * 확정 배치가 이번 계산에 쓴 입력 전부를 하나의 지문으로 압축한다.
     *
     * @param studentStops 학생ID → 승하차지ID (roster 조립 결과) — 집합이라 정렬한다
     * @param fixedStops   순번이 고정된 경유 지점 — 집합이라 정렬한다(순번 자체는 값에 포함되므로
     *                     정렬 기준을 waypointId 로 잡아도 최종 문자열의 순번 정보는 보존된다)
     */
    public static String of(Long academyId, Weekday weekday, Direction direction, OffsetDateTime departAt,
            GeoPoint origin, GeoPoint destination, Map<Long, Long> studentStops, List<FixedStop> fixedStops) {
        String canonical = String.join("|",
                String.valueOf(academyId),
                String.valueOf(weekday),
                String.valueOf(direction),
                String.valueOf(departAt),
                pointOf(origin),
                pointOf(destination),
                studentStopsOf(studentStops),
                fixedStopsOf(fixedStops));
        return hexDigest(canonical);
    }

    private static String pointOf(GeoPoint point) {
        return point.lat() + "," + point.lng();
    }

    private static String studentStopsOf(Map<Long, Long> studentStops) {
        return studentStops.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String fixedStopsOf(List<FixedStop> fixedStops) {
        return fixedStops.stream()
                .map(stop -> stop.waypointId() + ":" + stop.seq())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** {@link NoSuchAlgorithmException} 은 JVM 표준 알고리즘이라 발생하지 않는다 — 검사 예외를 감싸 던진다. */
    private static String hexDigest(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " 알고리즘을 찾을 수 없다", e);
        }
    }
}
