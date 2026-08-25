package src.backend.student.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 학부모-학생 연결 요청 상태 3종 — {@code link_request.status}(CHECK 로 강제)의 값 도메인이다.
 */
public enum LinkRequestStatus {

    /** 대기 중. */
    PENDING,
    /** 연결 완료. */
    COMPLETED,
    /** 기한 만료. */
    EXPIRED;

    /** {@link LinkRequestStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<LinkRequestStatus> {
        public Db() {
            super(LinkRequestStatus.class);
        }
    }
}
