package src.backend.run.navigation.dto;

import java.math.BigDecimal;

/**
 * 내비 응답의 출발지(API_SPEC §4.16) — {@code confirmed} 회차에서만 채워진다. {@code moving} 이면
 * 응답 자체에 이 필드가 없다 — 앱이 GPS 실측 위치를 쓰게 하려는 것이라, {@code null} 대신
 * <b>필드 부재</b>로 표현한다({@code NavigationResponse} 의 {@code @JsonInclude} 참고).
 */
public record NavOrigin(BigDecimal lat, BigDecimal lng, String name) {
}
