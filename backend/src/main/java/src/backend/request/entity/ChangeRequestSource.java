package src.backend.request.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 변경 요청 발생 경로 2종 — {@code change_request.source}(CHECK 로 강제)의 값 도메인이다.
 */
public enum ChangeRequestSource {

    /** 탑승 의사 토글에서 파생. */
    INTENT,
    /** 일일 변경 요청에서 직접 생성. */
    CHANGE_REQUEST;

    /** {@link ChangeRequestSource} 를 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ChangeRequestSource> {
        public Db() {
            super(ChangeRequestSource.class);
        }
    }
}
