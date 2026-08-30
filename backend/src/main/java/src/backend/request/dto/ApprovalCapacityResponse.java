package src.backend.request.dto;

/** 버스 정원 대조(API_SPEC §5.5 상세) — {@code assigned} 는 이 변경을 반영한 뒤의 인원이다. */
public record ApprovalCapacityResponse(int studentCapacity, int assigned) {
}
