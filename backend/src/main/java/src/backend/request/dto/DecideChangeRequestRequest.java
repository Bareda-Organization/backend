package src.backend.request.dto;

import jakarta.validation.constraints.NotNull;

/**
 * ②구간 승인 결정 요청(API_SPEC §5.6 {@code POST /staff/approvals/{id}/decide}).
 *
 * <p>{@code rejectReason}·{@code previewToken} 은 조건부 필수다(전자는 거절, 후자는 승인) —
 * {@link ChangeRequestCreateRequest} 와 같은 이유로 여기서 {@code @NotBlank} 를 걸지 않는다.
 * 조건부 필수는 서비스({@code ChangeRequestDecisionService})가 판정한다 — {@code preview_token} 은
 * 특히 <b>아예 빠진 요청도 캐시된 값과 다르므로 자연히 거부된다</b>(별도 null 검사가 필요 없다).
 */
public record DecideChangeRequestRequest(
        @NotNull Boolean approve,
        String rejectReason,
        String previewToken) {
}
