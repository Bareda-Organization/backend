package src.backend.request.dto;

/**
 * 버스 정원 대조(API_SPEC §5.5 상세) — {@code assigned} 는 {@code pending} 이면 이 변경을 반영한
 * 뒤의 가정 인원, 이미 결정된 건이면 지금 실제 탑승 인원({@code ABSENT} 제외)이다
 * ({@code ApprovalQueryService.decidedDetailOf} 참고).
 */
public record ApprovalCapacityResponse(int studentCapacity, int assigned) {
}
