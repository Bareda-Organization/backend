package src.backend.request.dto;

import java.time.OffsetDateTime;

/**
 * 탑승 의사 토글 응답(API_SPEC §3.6) — 필드 유무·값이 구간마다 갈린다.
 *
 * <p>{@code result} 는 {@link src.backend.request.domain.ChangeWindow} 이름을 그대로 노출하지 않고
 * 이 세 값({@code applied}·{@code pending_approval}·{@code applied_no_reroute})으로 따로 짓는다 —
 * {@code ChangeWindow} 는 "판정 구간"이고 {@code result} 는 "그 구간에서 실제로 벌어진 처리 결과"라
 * 뜻이 다르다(예: ①·③ 모두 즉시 반영이지만 재최적화 유무가 갈려 각각 {@code applied}·
 * {@code applied_no_reroute} 다).
 *
 * <p>{@code change_request_id} 는 {@link src.backend.request.entity.ChangeRequest} 의 PK 를 그대로
 * 옮긴다 — API_SPEC 예시가 {@code "creq_8812"} 처럼 문자열 접두를 쓰지만, 이 응답 계약을 새로
 * 만드는 이 태스크는 기존 코드 관례({@code AssignedManagerAccountView.managerId} 등 식별자는
 * 항상 순수 {@code Long})를 따른다 — 문서·코드 표기가 갈리는 지점이라 판단 근거로 보고에 남긴다.
 */
public record BoardingIntentToggleResponse(String result, boolean riding, String riderStatus,
        Long changeRequestId, int changeQuotaLeft, OffsetDateTime deadlineAt) {

    private static final String APPLIED = "applied";

    private static final String PENDING_APPROVAL = "pending_approval";

    private static final String APPLIED_NO_REROUTE = "applied_no_reroute";

    /** ①구간 즉시 반영(재최적화 없음, Ruling 198) 응답. */
    public static BoardingIntentToggleResponse applied(boolean riding, String riderStatus, int changeQuotaLeft) {
        return new BoardingIntentToggleResponse(APPLIED, riding, riderStatus, null, changeQuotaLeft, null);
    }

    /**
     * ②구간 승인 대기 응답 — {@code riding} 은 <b>바뀌지 않은 기존 값</b>을 그대로 싣는다(§3.6). 실제
     * 반영은 관리자 승인 이후이기 때문이다.
     */
    public static BoardingIntentToggleResponse pendingApproval(boolean existingRiding, String riderStatus,
            Long changeRequestId, int changeQuotaLeft, OffsetDateTime deadlineAt) {
        return new BoardingIntentToggleResponse(PENDING_APPROVAL, existingRiding, riderStatus, changeRequestId,
                changeQuotaLeft, deadlineAt);
    }

    /** ③구간 즉시 반영(재최적화 없음 — 순번 불변, {@code run_stop.skipped} 만) 응답. */
    public static BoardingIntentToggleResponse appliedNoReroute(boolean riding, String riderStatus,
            int changeQuotaLeft) {
        return new BoardingIntentToggleResponse(APPLIED_NO_REROUTE, riding, riderStatus, null, changeQuotaLeft,
                null);
    }
}
