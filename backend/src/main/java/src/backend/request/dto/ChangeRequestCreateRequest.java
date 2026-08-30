package src.backend.request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 일일 변경 신청 요청(P-06, API_SPEC §3.8).
 *
 * <p>{@code type} 을 enum 이 아니라 문자열로 받는다 — enum 바인딩 실패를 사양이 정한
 * {@code 422 VALIDATION_FAILED} 로 옮기는 판정은 서비스가 한다({@code WeeklyAddressEntryRequest} 와
 * 같은 형태). {@code newAddress} 는 {@code type=relocate} 일 때만 필수인 조건부 필드라 여기서
 * {@code @NotBlank} 를 걸지 않는다 — 조건부 필수는 서비스가 판정한다.
 */
public record ChangeRequestCreateRequest(
        @NotBlank String type,
        @NotNull Long runId,
        @Size(max = 255) String newAddress,
        @Size(max = 200) String reason) {
}
