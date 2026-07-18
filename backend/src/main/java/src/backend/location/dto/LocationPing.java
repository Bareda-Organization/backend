package src.backend.location.dto;

import java.time.LocalDateTime;

/**
 * 한 번의 위치 보고(ping). 실시간·휘발성 데이터라 JPA 엔티티가 아니라 단순 값 객체로 두고,
 * {@code LocationRepository}(현재 in-memory, 추후 Redis) 에 "학생별 최신 1건"으로 저장한다.
 *
 * @param studentId  대상 학생
 * @param tenantId   학생 소속 학원(관리자 테넌트 조회 시 사용)
 * @param lat        위도
 * @param lng        경도
 * @param recordedAt 좌표 기록 시각
 * @param origin     좌표 출처(GPS/MOCK)
 */
public record LocationPing(
        Long studentId,
        Long tenantId,
        double lat,
        double lng,
        LocalDateTime recordedAt,
        LocationOrigin origin) {
}
