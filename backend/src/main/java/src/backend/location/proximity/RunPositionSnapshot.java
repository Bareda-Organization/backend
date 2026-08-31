package src.backend.location.proximity;

import java.math.BigDecimal;

/**
 * 근접 알림(NTF-04) 판정에 필요한 만큼만 담은 최신 위치 — Redis 키 {@code run:{runId}:position}
 * 의 JSON 값 중 {@code lat}·{@code lng} 만 읽는다(T1 소유 계약). {@code recordedAt}·{@code receivedAt}·
 * {@code currentStopName} 은 이 판정에 쓰지 않아 옮기지 않는다 — 목표 13·15 밖의 필드까지 옮기면
 * 근접 판정과 무관한 값이 이 레코드의 계약처럼 보인다.
 */
public record RunPositionSnapshot(BigDecimal lat, BigDecimal lng) {
}
