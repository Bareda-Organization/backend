package src.backend.request.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 변경 요청 종류 2종 — {@code change_request.type}(CHECK 로 강제)의 값 도메인이다.
 */
public enum ChangeRequestType {

    /** 승하차지 변경. {@code new_address} 필수(CHECK). */
    RELOCATE,
    /** 탑승 취소. */
    CANCEL;

    /** {@link ChangeRequestType} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ChangeRequestType> {
        public Db() {
            super(ChangeRequestType.class);
        }
    }
}
