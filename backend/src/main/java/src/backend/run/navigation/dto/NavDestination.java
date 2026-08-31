package src.backend.run.navigation.dto;

import java.math.BigDecimal;

/** 내비 응답의 최종 목적지(API_SPEC §4.16) — {@code seq} 가 없다: 목적지는 항상 마지막이다. */
public record NavDestination(BigDecimal lat, BigDecimal lng, String name, Long stopId) {
}
