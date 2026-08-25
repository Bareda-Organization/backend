package src.backend.academy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 학원 관계자 — 학원당 1명 정원을 DB 레벨(UNIQUE)로 강제하는 자리다(ERD §3.1 · ACAD-05·06 · O-02).
 * 이름·연락처는 {@code account} 가 보유하므로 여기서 중복 보관하지 않는다.
 */
@Entity
@Table(name = "academy_staff")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AcademyStaff extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Convert(converter = StaffStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private StaffStatus status;

    private AcademyStaff(Long academyId, Long accountId) {
        this.academyId = academyId;
        this.accountId = accountId;
        this.status = StaffStatus.ACTIVE;
    }

    /** 메인 관리자가 관계자 가입 요청을 승인할 때 생성한다(O-02) — 재직 상태로 시작한다. */
    public static AcademyStaff uponApproval(Long academyId, Long accountId) {
        return new AcademyStaff(academyId, accountId);
    }
}
