package src.backend.account.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 메인 관리자(플랫폼 관리자) — 전 학원 범위 권한 보유자의 명시적 등록부다. {@code account.role}
 * 만으로도 판정 가능하나 발급 사실을 별도 레코드로 남겨 무단 역할 변경을 관측 가능하게
 * 한다(ERD §3.1 · FEATURE_SPEC §1.2 · §3.2 · O-01~06).
 *
 * <p>{@code updated_at} 이 없어({@code created_at} 만 존재) {@code BaseTimeEntity} 를 상속하지
 * 않는다. {@code created_at} 은 DB 에 {@code DEFAULT now()} 가 있지만, 이 컬럼을 관리하는
 * 감사(auditing) 메커니즘은 {@code BaseTimeEntity} 전용이라({@code JpaAuditingConfig} 참고)
 * 그 대상 밖인 이 필드는 호출자가 값을 직접 넘긴다.
 */
@Entity
@Table(name = "system_admin")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private SystemAdmin(Long accountId, OffsetDateTime createdAt) {
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    /** 플랫폼 관리자 권한을 내부 발급하는 시점에 생성한다 — 가입 절차를 거치지 않는다. */
    public static SystemAdmin uponGrant(Long accountId, OffsetDateTime createdAt) {
        return new SystemAdmin(accountId, createdAt);
    }
}
