package src.backend.monitoring.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * T1 이 {@code run:{runId}:position} 키에 쓰는 위치 캐시 값을 그대로 옮긴 읽기 전용 사본이다(관제
 * 모듈 전용, Phase 13).
 *
 * <p>필드 이름은 T1 의 계약 문서에 적힌 그대로다(평문 camelCase — {@code lat}·{@code lng}·
 * {@code recordedAt}·{@code receivedAt}·{@code currentStopName}). 다른 소비 모듈({@code student.query}·
 * {@code location.proximity})과 같은 5필드를 다시 선언하는 이유는 {@code RunPositionRedisValue} 자바독의
 * 관례를 따르기 위해서다 — 리더 클래스는 모듈마다 갈라도 되지만, 그 리더가 역직렬화할 레코드는 실제
 * 와이어의 필드 집합과 정확히 같아야 알 수 없는 필드로 인한 역직렬화 실패가 나지 않는다.
 */
public record RunPositionSnapshot(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt,
        OffsetDateTime receivedAt, String currentStopName) {
}
