package src.backend.student.entity;

import jakarta.persistence.Column;
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
 * 보호자 — 자녀 N명 연결의 기준점이다. {@code student_id} 를 직접 부착하지 않고
 * {@code guardian_student} 연결 테이블로만 표현한다(ERD §3.2 · FEATURE_SPEC §3.2 · P-02 · ATT-03).
 *
 * <p>{@code account_id} 는 학부모 가입으로만 생기므로 NN 이다 — {@code student.account_id} 와 달리
 * 계정 미연결 상태가 없다.
 */
@Entity
@Table(name = "guardian")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Guardian extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "phone", length = 30, nullable = false)
    private String phone;

    private Guardian(Long academyId, Long accountId, String name, String phone) {
        this.academyId = academyId;
        this.accountId = accountId;
        this.name = name;
        this.phone = phone;
    }

    /** 학부모가 가입 form 을 제출해 승인되는 시점에 생성한다(P-02). */
    public static Guardian forSignup(Long academyId, Long accountId, String name, String phone) {
        return new Guardian(academyId, accountId, name, phone);
    }
}
