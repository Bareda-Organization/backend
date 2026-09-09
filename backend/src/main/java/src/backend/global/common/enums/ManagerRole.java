package src.backend.global.common.enums;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 매니저 배치 역할 2종 — {@code manager.role} · {@code assignment.role}(둘 다 CHECK 로 강제)과
 * {@code emergency_alert.raised_by_role}(CHECK 부재, 애플리케이션 레벨로만 강제)이 공유하는 값 도메인이다.
 */
public enum ManagerRole {

    DRIVER, ESCORT;

    /** {@link ManagerRole} 을 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<ManagerRole> {
        public Db() {
            super(ManagerRole.class);
        }
    }
}
