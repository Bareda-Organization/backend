package src.backend.student.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.GuardianStudent;

/** {@link GuardianStudent} 영속성 접근. */
public interface GuardianStudentRepository extends JpaRepository<GuardianStudent, Long> {
    /** 해지되지 않은(unlinked_at IS NULL) 연결만 센다 — §2.10 linked_student_count. */
    @AcademyScopeExempt(reason = "§2.10 본인 연결 학생 수 — guardian_student 는 guardian 부모 경유라 보호자가 곧 학원 범위. "
            + "호출부가 토큰의 accountId 로 찾은 Guardian 의 id 만 넘긴다는 전제 — 요청 파라미터의 guardianId 를 "
            + "넘기면 타 학원 보호자의 연결 수가 새어 이 예외가 우회로가 된다")
    long countByGuardianIdAndUnlinkedAtIsNull(Long guardianId);

    /**
     * 같은 자녀가 이미 연결돼 있는지 본다 — 중복 연결은 {@code 409 ALREADY_LINKED}(§8.5).
     *
     * <p>{@code unlinked_at} 을 조건에 넣지 않는다 — 유일성을 강제하는 것은
     * {@code uk_guardian_student(guardian_id, student_id)} 이고 그 제약에 해지 여부가 부재하다.
     * 해지된 연결을 "없는 것" 으로 세면 재연결이 검사를 지나 DB 위반으로 떨어져 {@code 500} 이 된다.
     */
    @AcademyScopeExempt(reason = "guardian_student 는 guardian 부모 경유라 보호자가 곧 학원 범위(ERD §6.1). "
            + "호출부가 토큰 계정으로 찾은 Guardian 의 id 와 학원 조건으로 좁혀 조회한 Student 의 id 만 넘긴다는 전제 — "
            + "요청 파라미터의 guardianId 를 넘기면 타 학원 보호자의 연결 여부가 새어 이 예외가 우회로가 된다")
    boolean existsByGuardianIdAndStudentId(Long guardianId, Long studentId);

    /**
     * 이 보호자가 <b>지금</b> 이 자녀에 닿을 수 있는가 — 학부모 API 접근 범위 판정의 근거다(§1.5).
     *
     * <p>{@code unlinked_at IS NULL} 이 조건에 들어간다. 위 {@link #existsByGuardianIdAndStudentId}
     * 와 <b>일부러 다른 조건</b>이다 — 저쪽은 UNIQUE 제약(해지 여부 부재)과 짝을 맞춰 재연결을 막는
     * 물음이고, 이쪽은 "지금 보호자인가" 를 묻는다. 해지된 연결을 살아 있는 것으로 세면 퇴원으로
     * 관계가 끝난 뒤에도 옛 보호자가 자녀 정보를 계속 조회한다(ERD §7.1 · UF-P-01).
     *
     * <p>학원 조건은 {@code student} 부모를 조인해 건다(ERD §6.1 부모 경유).
     */
    @Query("""
            SELECT COUNT(gs) > 0 FROM GuardianStudent gs
            JOIN Student s ON s.id = gs.studentId
            WHERE gs.guardianId = :guardianId
              AND gs.studentId = :studentId
              AND gs.unlinkedAt IS NULL
              AND s.academyId = :academyId
            """)
    boolean existsActiveLink(@Param("guardianId") Long guardianId, @Param("studentId") Long studentId,
            @Param("academyId") Long academyId);

    /**
     * 한 학생의 <b>살아 있는</b> 보호자 연결 전부 — 퇴원 시 해제 대상이다(STU-04 · ERD §7.1).
     *
     * <p>위 {@link #findLinkedChildren} 의 반대 방향이다. 학생 1명에 보호자가 여럿일 수 있어 목록으로
     * 돌려주며, 이미 해제된 것은 조건에서 빠져 재퇴원이 옛 해제 시각을 덮지 않는다.
     *
     * <p>{@code @Modifying} 대량 UPDATE 가 아니라 엔티티를 꺼내 오는 이유는 상태 전이를 엔티티
     * 메서드에 두기 때문이다(횡단 규칙 2). 경합 전이가 아니라 조건부 UPDATE 가 필요한 자리도 아니고,
     * 한 학생의 보호자는 실무상 한둘이라 꺼내는 비용이 문제가 되는 자리도 아니다.
     *
     * <p>학원 조건은 {@code student} 부모를 조인해 건다(ERD §6.1 부모 경유).
     */
    @Query("""
            SELECT gs FROM GuardianStudent gs
            JOIN Student s ON s.id = gs.studentId
            WHERE gs.studentId = :studentId
              AND gs.unlinkedAt IS NULL
              AND s.academyId = :academyId
            """)
    List<GuardianStudent> findActiveLinksOfStudent(@Param("studentId") Long studentId,
            @Param("academyId") Long academyId);

    /**
     * 한 보호자의 자녀 목록(ATT-03, §3.1) — 해지되지 않은 연결만이다.
     *
     * <p>{@link LinkedChild} 로 <b>네 값만</b> 꺼낸다. {@code Student} 를 통째로 꺼내면 사진·특이사항이
     * 함께 손에 들어오고, 그러면 응답 조립이 그것을 빼는 데 성공해야만 §1.12 가 지켜진다.
     *
     * <p>정렬을 고정한다 — 자녀 선택 UI(2명 이상일 때 노출)의 순서가 새로고침마다 바뀌면 사용자가
     * 매번 다른 자리에서 같은 아이를 찾는다.
     */
    @Query("""
            SELECT gs.studentId AS studentId, s.name AS name, s.className AS className,
                   gs.linkedAt AS linkedAt
            FROM GuardianStudent gs
            JOIN Student s ON s.id = gs.studentId
            WHERE gs.guardianId = :guardianId
              AND gs.unlinkedAt IS NULL
              AND s.academyId = :academyId
            ORDER BY gs.linkedAt ASC, gs.studentId ASC
            """)
    List<LinkedChild> findLinkedChildren(@Param("guardianId") Long guardianId,
            @Param("academyId") Long academyId);

    /**
     * 학생들의 보호자 연락처를 한 번에 모은다(A-10, API_SPEC §5.11 {@code items[].guardian_phone}).
     *
     * <p><b>{@code guardian.phone} 이 아니라 {@code account.phone} 을 읽는다.</b> 두 값은 가입 시점에
     * 같게 출발하지만 보호자가 번호를 바꾸는 곳은 계정이라, {@code guardian} 쪽을 읽으면 명단이 옛
     * 값을 계속 보여 준다 — 연락처를 학생에 복제하지 않기로 한 판단(A-10)이 막으려던 사고와 형태가 같다.
     *
     * <p>{@code unlinked_at} 이 채워진 연결은 뺀다 — 퇴원·연결 해제 뒤에도 남으면 더 이상 보호자가
     * 아닌 사람의 번호가 명단에 남는다.
     *
     * <p>학생 1명에 보호자가 여럿일 수 있어 <b>정렬을 고정</b>한다. 없으면 어느 번호가 대표로 실릴지
     * DB 가 정하고, 그 순서는 계약이 아니라 같은 화면이 새로고침마다 다른 번호를 보일 수 있다.
     */
    @Query("""
            SELECT gs.studentId AS studentId, a.phone AS phone
            FROM GuardianStudent gs
            JOIN Guardian g ON g.id = gs.guardianId
            JOIN Account a ON a.id = g.accountId
            WHERE g.academyId = :academyId
              AND gs.studentId IN :studentIds
              AND gs.unlinkedAt IS NULL
            ORDER BY gs.studentId ASC, gs.linkedAt ASC, gs.id ASC
            """)
    List<GuardianPhone> findGuardianPhonesByAcademyId(@Param("academyId") Long academyId,
            @Param("studentIds") List<Long> studentIds);
}
