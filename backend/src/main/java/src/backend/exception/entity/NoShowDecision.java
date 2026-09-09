package src.backend.exception.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 미승차 대응 판단 2종 — {@code no_show_case.decision} · {@code no_show_contact_attempt.decision}
 * (둘 다 CHECK 로 강제)이 공유하는 값 도메인이다. 두 테이블 모두 {@code exception} 모듈 소속이라
 * Ruling 39 기준(2개 이상 "모듈" 공유)에는 해당하지 않아 {@code global/common/enums} 로 올리지
 * 않았다.
 */
public enum NoShowDecision {

    /** 대기 없이 출발. */
    DEPART,
    /** 재시도(연락 등) 후 재판단. */
    RETRY;

    /** {@link NoShowDecision} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<NoShowDecision> {
        public Db() {
            super(NoShowDecision.class);
        }
    }
}
