package src.backend.global.common.enums;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 계정 역할 6종 — {@code account.role}(CHECK 로 강제)과 {@code signup_request.requested_role}·
 * {@code notification_log.recipient_role}(둘 다 CHECK 부재, 애플리케이션 레벨로만 강제)이 공유하는
 * 값 도메인이다.
 *
 * <p>CHECK 가 없는 두 컬럼은 스키마가 값을 보장하지 않는다 — 잘못된 값이 들어가도 쓸 때는 조용히
 * 성공하고, 그 행을 다시 읽어 {@link Role.Db#convertToEntityAttribute} 를 타는 순간에야
 * {@link Enum#valueOf} 가 실패한다.
 */
public enum Role {

    /** 학부모. */
    PARENT,
    /** 학생. */
    STUDENT,
    /** 운전기사. */
    DRIVER,
    /** 동승자. */
    ESCORT,
    /** 학원 관계자(메인 관리자 제외). */
    STAFF,
    /** 플랫폼 전 학원 범위 권한 보유자. */
    SYSTEM_ADMIN;

    /** {@link Role} 을 소문자 snake_case 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<Role> {
        public Db() {
            super(Role.class);
        }
    }
}
