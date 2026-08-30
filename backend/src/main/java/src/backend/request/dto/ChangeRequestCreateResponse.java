package src.backend.request.dto;

import java.time.OffsetDateTime;
import java.util.Locale;

import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;

/**
 * 일일 변경 신청 접수 결과(API_SPEC §3.8 {@code 201}).
 *
 * <p>{@code result} 는 저장 컬럼이 아니라 {@code status} 에서 유도한다 — ①구간은 접수와 동시에
 * {@link ChangeRequestStatus#APPROVED} 로 저장되므로(Ruling 198, 재최적화 없이 즉시 반영) 그 값만
 * 보고 "applied" 인지 "pending_approval" 인지 가릴 수 있다.
 */
public record ChangeRequestCreateResponse(Long changeRequestId, String status, String result,
        OffsetDateTime deadlineAt) {

    public static ChangeRequestCreateResponse from(ChangeRequest changeRequest) {
        String result = changeRequest.getStatus() == ChangeRequestStatus.APPROVED ? "applied" : "pending_approval";
        return new ChangeRequestCreateResponse(changeRequest.getId(),
                changeRequest.getStatus().name().toLowerCase(Locale.ROOT), result, changeRequest.getDeadlineAt());
    }
}
