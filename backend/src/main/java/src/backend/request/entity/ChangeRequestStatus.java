package src.backend.request.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 변경 요청 처리 상태 4종 — {@code change_request.status}(CHECK 로 강제)의 값 도메인이다.
 * {@code REJECTED} 로 갈 때는 {@code reject_reason} 필수가 별도 CHECK 로 함께 강제된다.
 */
public enum ChangeRequestStatus {

    /** 승인 대기. */
    PENDING,
    /** 승인됨. */
    APPROVED,
    /** 거절됨. */
    REJECTED,
    /** 시한 초과 등으로 자동 거절됨. */
    AUTO_REJECTED;

    /** {@link ChangeRequestStatus} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ChangeRequestStatus> {
        public Db() {
            super(ChangeRequestStatus.class);
        }
    }
}
