package src.backend.request.dto;

import java.time.OffsetDateTime;

/**
 * ②구간 승인 결정 응답(API_SPEC §5.6) — {@code status} 는 {@code approved}·{@code rejected} 둘뿐이다
 * ({@code auto_rejected} 는 이 엔드포인트가 만들지 않는다).
 *
 * @param routeVersion 승인이면 새로 배포된 버전 번호(+1), 거절이면 <b>기존</b> 버전 번호(유지) —
 *                      확정 노선이 아직 없는 회차는 {@code null} 일 수 없다(이 엔드포인트는 이미
 *                      확정된 회차의 ②구간 요청만 다룬다).
 */
public record DecideChangeRequestResponse(
        String status,
        boolean stopRemoved,
        Integer routeVersion,
        Long decidedBy,
        OffsetDateTime decidedAt) {
}
