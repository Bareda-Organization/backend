package src.backend.location.dto;

import java.time.LocalDateTime;

/**
 * 버스 1대의 위치 보고(ping) — 학생 단위 {@link LocationPing}과 나란한 버스 단위 미러(F1).
 * 실시간·휘발성 데이터라 JPA 엔티티가 아니라 단순 값 객체로 두고, {@code BusLocationRepository}에
 * "버스별 최신 1건"으로 저장한다.
 */
public record BusLocationPing(
        Long busId,
        Long tenantId,
        double lat,
        double lng,
        LocalDateTime recordedAt,
        LocationOrigin origin) {
}
