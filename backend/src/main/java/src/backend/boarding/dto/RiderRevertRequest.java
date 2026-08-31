package src.backend.boarding.dto;

/** 상태 정정 요청(API_SPEC §4.7) — {@code reason} 은 선택이라 검증 애너테이션을 붙이지 않는다. */
public record RiderRevertRequest(String reason) {
}
