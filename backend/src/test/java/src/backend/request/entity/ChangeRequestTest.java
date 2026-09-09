package src.backend.request.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@link ChangeRequest} 상태 전이 메서드 단위 테스트 — DB·Spring 컨텍스트 없이 순수 도메인 규칙만
 * 검증한다.
 */
class ChangeRequestTest {

    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-09-01T07:00:00+09:00");

    private ChangeRequest pendingRequest() {
        return ChangeRequest.forRequest(1L, 1L, 1L, ChangeRequestSource.CHANGE_REQUEST,
                ChangeRequestType.CANCEL, (short) 2, 1L, REQUESTED_AT);
    }

    @Test
    void approve_는_상태를_approved_로_바꾸고_처리자_처리시각_적용정보를_기록한다() {
        ChangeRequest request = pendingRequest();
        OffsetDateTime decidedAt = REQUESTED_AT.plusMinutes(5);

        request.approve(2L, decidedAt, true, 10L);

        assertThat(request.getStatus()).isEqualTo(ChangeRequestStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo(2L);
        assertThat(request.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(request.getStopRemoved()).isTrue();
        assertThat(request.getAppliedRouteVersionId()).isEqualTo(10L);
    }

    @Test
    void reject_는_상태를_rejected_로_바꾸고_사유를_기록한다() {
        ChangeRequest request = pendingRequest();
        OffsetDateTime decidedAt = REQUESTED_AT.plusMinutes(5);

        request.reject(2L, decidedAt, "정원 초과");

        assertThat(request.getStatus()).isEqualTo(ChangeRequestStatus.REJECTED);
        assertThat(request.getDecidedBy()).isEqualTo(2L);
        assertThat(request.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(request.getRejectReason()).isEqualTo("정원 초과");
    }

    @Test
    void autoReject_는_decided_by_없이_상태만_auto_rejected_로_바꾼다() {
        ChangeRequest request = pendingRequest();
        OffsetDateTime decidedAt = REQUESTED_AT.plusMinutes(30);

        request.autoReject(decidedAt);

        assertThat(request.getStatus()).isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
        assertThat(request.getDecidedBy())
                .as("자동 거절은 서버가 한 일이라 decided_by 가 없다")
                .isNull();
        assertThat(request.getDecidedAt()).isEqualTo(decidedAt);
    }

    @Test
    void 이미_승인된_건을_다시_승인하면_409_APPROVAL_ALREADY_DECIDED_다() {
        ChangeRequest request = pendingRequest();
        request.approve(2L, REQUESTED_AT.plusMinutes(5), false, null);

        assertThatThrownBy(() -> request.approve(3L, REQUESTED_AT.plusMinutes(6), false, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    @Test
    void 이미_거절된_건을_다시_거절하면_409_APPROVAL_ALREADY_DECIDED_다() {
        ChangeRequest request = pendingRequest();
        request.reject(2L, REQUESTED_AT.plusMinutes(5), "사유");

        assertThatThrownBy(() -> request.reject(3L, REQUESTED_AT.plusMinutes(6), "다른 사유"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }

    @Test
    void 이미_처리된_건은_자동거절도_거부한다() {
        ChangeRequest request = pendingRequest();
        request.reject(2L, REQUESTED_AT.plusMinutes(5), "사유");

        assertThatThrownBy(() -> request.autoReject(REQUESTED_AT.plusMinutes(30)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED));
    }
}
