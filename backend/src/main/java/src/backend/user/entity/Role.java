package src.backend.user.entity;

/**
 * 사용자 역할 5계층. User 에 직접 박지 않고 UserTenantRole(학원별 역할)로 부여한다.
 */
public enum Role {
    STUDENT,          // 학생
    PARENT,           // 학부모
    DRIVER,           // 운전기사
    ACADEMY_ADMIN,    // 학원 관리자
    PLATFORM_ADMIN    // 플랫폼 관리자
}
