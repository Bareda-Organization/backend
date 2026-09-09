package src.backend.student.geocoding.spec;

import java.math.BigDecimal;

/**
 * 지오코딩이 돌려준 한 지점 — 좌표와 공급자가 정규화한 주소 표기다.
 *
 * <p>{@code double} 이 아니라 {@link BigDecimal} 인 것은 {@code stop.lat}·{@code lng} 가
 * {@code numeric(9,6)} 이기 때문이다(ERD) — 이진 부동소수로 받으면 저장·재조회 사이에 값이 바뀌어
 * "같은 주소는 같은 좌표" 라는 근접 병합의 전제가 흔들린다.
 *
 * @param lat         위도
 * @param lng         경도
 * @param displayName 공급자가 정규화한 표기(네이버 {@code roadAddress}) — {@code stop.name} 의 재료다
 */
public record GeocodedPoint(BigDecimal lat, BigDecimal lng, String displayName) {
}
