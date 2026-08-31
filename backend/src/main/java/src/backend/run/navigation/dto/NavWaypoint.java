package src.backend.run.navigation.dto;

import java.math.BigDecimal;

/** 내비 응답의 경유지 한 자리(API_SPEC §4.16) — 배열 순서가 곧 주행 순서다. */
public record NavWaypoint(BigDecimal lat, BigDecimal lng, String name, Long stopId, int seq) {
}
