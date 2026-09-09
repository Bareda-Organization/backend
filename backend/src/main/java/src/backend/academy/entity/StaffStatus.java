package src.backend.academy.entity;

import jakarta.persistence.Converter;

import src.backend.global.common.converter.LowerCaseEnumConverter;

/**
 * 학원 관계자 재직 상태 2종 — {@code academy_staff.status} CHECK. 값 자체는 {@link AcademyStatus}
 * 와 같지만(둘 다 active/inactive) 학원의 운영 상태와 관계자 개인의 재직 상태는 서로 다른 생명주기라
 * 별개 타입으로 둔다 — 하나가 바뀌어도 다른 하나는 영향받지 않아야 한다.
 */
public enum StaffStatus {

    /** 재직 중 — 권한 보유. */
    ACTIVE,
    /** 퇴사 — 즉시 권한 회수. */
    INACTIVE;

    /** {@link StaffStatus} 를 소문자 컬럼 값으로 잇는 JPA 컨버터. */
    @Converter
    public static class Db extends LowerCaseEnumConverter<StaffStatus> {
        public Db() {
            super(StaffStatus.class);
        }
    }
}
