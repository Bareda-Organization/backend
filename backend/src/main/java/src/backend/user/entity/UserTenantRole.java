package src.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.tenant.entity.Tenant;

/**
 * User ↔ Tenant 의 N:M 연결 + 역할.
 * "이 사용자가, 이 학원에서, 이 역할" 을 한 행으로 표현한다.
 * 예) 같은 학부모가 A학원=PARENT, B학원=PARENT 각각의 행을 가진다.
 * 플랫폼 관리자는 특정 학원에 속하지 않는 전역 역할이라 tenant 가 null 인 행을 가진다.
 */
@Entity
@Table(name = "user_tenant_role",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "tenant_id", "role"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTenantRole extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)   // 플랫폼 관리자는 tenant 가 null (전역 역할)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Builder
    public UserTenantRole(User user, Tenant tenant, Role role) {
        this.user = user;
        this.tenant = tenant;
        this.role = role;
    }
}
