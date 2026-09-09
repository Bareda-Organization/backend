package src.backend.run.navigation.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code GET /runs/{runId}/navigation} 응답(API_SPEC §4.16, RUN-08) — 서버가 순서·제외·잘림까지
 * 정한 좌표열이다. 딥링크 URL 은 여기 없다 — 그것은 앱이 {@code provider} 를 보고 조립한다
 * (Ruling 201).
 *
 * <p>{@code origin} 에만 {@link JsonInclude}({@code NON_NULL})를 붙인다 — {@code moving} 이면
 * 이 필드가 아예 없어야 앱이 "서버가 안 줬으니 GPS 를 쓴다" 로 읽는다. {@code null} 을 그대로
 * 내보내면 값이 있는 것과 구별되지 않는 필드가 하나 늘 뿐이다.
 */
public record NavigationResponse(String provider, @JsonInclude(JsonInclude.Include.NON_NULL) NavOrigin origin,
        List<NavWaypoint> waypoints, NavDestination destination, boolean truncated,
        @JsonInclude(JsonInclude.Include.NON_NULL) String truncatedReason, int totalRemainingStops) {
}
