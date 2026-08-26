package src.backend.student.entity;

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
 * 보호자 ↔ 학생 연결 — 다자녀를 재가입 없이 연결 추가로 처리하는 유일 경로이며, 학부모 API 의
 * 접근 범위(연결된 자녀만) 판정 근거다(ERD §3.2 · P-02 · ATT-03 · API_SPEC §1.5).
 *
 * <p>{@code created_at}/{@code updated_at} 쌍이 없어({@code linked_at}·{@code unlinked_at})
 * {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Entity
@Table(name = "guardian_student")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuardianStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "guardian_id", nullable = false)
    private Long guardianId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "unlinked_at")
    private OffsetDateTime unlinkedAt;

    private GuardianStudent(Long guardianId, Long studentId, OffsetDateTime linkedAt) {
        this.guardianId = guardianId;
        this.studentId = studentId;
        this.linkedAt = linkedAt;
    }

    /** 연결 요청·코드 대조가 끝나 자녀 연결이 성립하는 시점에 생성한다(P-02). */
    public static GuardianStudent uponLink(Long guardianId, Long studentId, OffsetDateTime linkedAt) {
        return new GuardianStudent(guardianId, studentId, linkedAt);
    }

    /**
     * 퇴원으로 보호자 관계가 끝난 시점을 기록한다(STU-04 · ERD §7.1 · UF-P-01).
     *
     * <p><b>행을 지우지 않는 것이 규정이다.</b> 지우면 목록에서 빠지는 결과는 같지만 "이 학생을 누가
     * 데려갔었나" 를 되짚을 수단이 사라진다. 그래서 소멸이 아니라 <b>끝난 시각의 기록</b>이다.
     *
     * <p>이미 해제된 연결은 다시 덮지 않는다 — 덮으면 관계가 끝난 시각이 마지막 조작 시각으로 밀려,
     * 보존하려던 이력이 그 순간 거짓이 된다({@code Student#withdraw} 가 재퇴원을 막는 것과 같은 근거).
     */
    public void unlink(OffsetDateTime at) {
        if (unlinkedAt == null) {
            this.unlinkedAt = at;
        }
    }
}
