package src.backend.student.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Guardian;
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
     * <p>{@link LinkedChild} 로 <b>다섯 값만</b> 꺼낸다. {@code Student} 를 통째로 꺼내면 사진·특이사항이
     * 함께 손에 들어오고, 그러면 응답 조립이 그것을 빼는 데 성공해야만 §1.12 가 지켜진다.
     *
     * <p>정렬을 고정한다 — 자녀 선택 UI(2명 이상일 때 노출)의 순서가 새로고침마다 바뀌면 사용자가
     * 매번 다른 자리에서 같은 아이를 찾는다.
     */
    @Query("""
            SELECT gs.studentId AS studentId, s.name AS name, s.className AS className, s.grade AS grade,
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
     * <p><b>{@code guardian.phone}(학원이 관리하는 보호자 연락처)을 읽는다</b>(2026-09-23, Ruling 326). 관계자가 고칠
     * 수 있는 값이 이것이다. 예전에는 {@code account.phone}(로그인·계정 복구 번호)을 읽었는데, 관계자에게 그 번호를
     * 고치게 하면 복구 번호를 자기 번호로 바꿔 학부모 계정을 가로챌 수 있다. 두 값은 가입 시점에 같게 출발한다.
     *
     * <p>{@code unlinked_at} 이 채워진 연결은 뺀다 — 퇴원·연결 해제 뒤에도 남으면 더 이상 보호자가
     * 아닌 사람의 번호가 명단에 남는다.
     *
     * <p>학생 1명에 보호자가 여럿일 수 있어 <b>정렬을 고정</b>한다. 없으면 어느 번호가 대표로 실릴지
     * DB 가 정하고, 그 순서는 계약이 아니라 같은 화면이 새로고침마다 다른 번호를 보일 수 있다.
     */
    @Query("""
            SELECT gs.studentId AS studentId, g.phone AS phone
            FROM GuardianStudent gs
            JOIN Guardian g ON g.id = gs.guardianId
            WHERE g.academyId = :academyId
              AND gs.studentId IN :studentIds
              AND gs.unlinkedAt IS NULL
            ORDER BY gs.studentId ASC, gs.linkedAt ASC, gs.id ASC
            """)
    List<GuardianPhone> findGuardianPhonesByAcademyId(@Param("academyId") Long academyId,
            @Param("studentIds") List<Long> studentIds);

    /** 학생 한 명에 연결된 보호자 전부(먼저 연결된 차례) — 관계자 학생 상세·보호자 연락처 수정(Ruling 326). */
    @Query("""
            SELECT g FROM GuardianStudent gs
            JOIN Guardian g ON g.id = gs.guardianId
            WHERE g.academyId = :academyId
              AND gs.studentId = :studentId
              AND gs.unlinkedAt IS NULL
            ORDER BY gs.linkedAt ASC, gs.id ASC
            """)
    List<Guardian> findLinkedGuardians(@Param("academyId") Long academyId, @Param("studentId") Long studentId);

    /**
     * 학생들의 보호자 계정(알림 수신자)을 한 번에 모은다(Phase 9 RUN-05·06, API_SPEC §9.7
     * {@code run_started}·{@code alighting}).
     *
     * <p>{@link #findGuardianPhonesByAcademyId} 와 조인 구조는 같지만 {@code phone} 대신
     * {@code account.id}·{@code name} 을 읽는다 — 알림 적재({@code notification_log})가 필요로
     * 하는 것이 연락처 문자열이 아니라 수신자 계정이기 때문이다.
     *
     * <p>학생 1명에 보호자가 여럿일 수 있어 <b>한 학생당 한 행만</b> 알림에 쓰기로 한 자리
     * (goal 9 "알림 행 수 = 자동 하차 인원 수")에서는 <b>호출부가 이 목록을 순회하며 학생당 첫
     * 행만 취한다</b> — 그 "첫 행" 을 결정론적으로 만드는 것이 아래 정렬이다. 정렬 기준은
     * {@link #findGuardianPhonesByAcademyId} 와 동일하다(같은 근거 — 없으면 대표 보호자가
     * 새로고침마다 바뀐다).
     */
    @Query("""
            SELECT gs.studentId AS studentId, a.id AS accountId, a.name AS name, s.name AS studentName
            FROM GuardianStudent gs
            JOIN Guardian g ON g.id = gs.guardianId
            JOIN Account a ON a.id = g.accountId
            JOIN Student s ON s.id = gs.studentId
            WHERE g.academyId = :academyId
              AND gs.studentId IN :studentIds
              AND gs.unlinkedAt IS NULL
            ORDER BY gs.studentId ASC, gs.linkedAt ASC, gs.id ASC
            """)
    List<GuardianAccountRecipient> findGuardianAccountsByAcademyId(@Param("academyId") Long academyId,
            @Param("studentIds") List<Long> studentIds);

    /**
     * 승하차 알림(API_SPEC §4.6 {@code boarded}·{@code alighted}·{@code no_show}, BR-134)의 학부모
     * 수신자를 <b>여러 학생에 대해 한 번에</b> 모은다 — 학생 1명에 보호자가 여럿일 수 있어 학생별로
     * 여러 행이 돌아온다. 승하차지에 있는 학생 수만큼 반복 호출하던 것을 학생 id 목록 하나로 묶는다.
     *
     * <p>{@code guardian.name} 을 읽는다 — {@link #findGuardianPhonesByAcademyId} 가 전화번호를 읽는
     * {@code guardian} 과 같은 테이블이라 별도 조인이 필요 없다(둘 다 Ruling 326 이후 {@code guardian}
     * 이 원본, {@code account} 는 더 이상 관련 없다). {@link #findGuardianAccountsByAcademyId} 를
     * 그대로 쓰지 못하는 이유는 그쪽이 {@code account.name} 을 읽기 때문이다 — 바꾸면 이 알림 기록의
     * {@code recipient_name} 이 달라진다.
     *
     * <p>{@code unlinked_at} 이 채워진 연결은 뺀다 — 퇴원·연결 해제된 보호자에게는 보내지 않는다.
     * 학원 조건은 {@code student} 부모를 조인해 건다(ERD §6.1 부모 경유).
     */
    @Query("""
            SELECT gs.studentId AS studentId, g.accountId AS accountId, g.name AS name, s.name AS studentName
            FROM GuardianStudent gs
            JOIN Guardian g ON g.id = gs.guardianId
            JOIN Student s ON s.id = gs.studentId
            WHERE gs.studentId IN :studentIds
              AND gs.unlinkedAt IS NULL
              AND s.academyId = :academyId
            ORDER BY gs.studentId ASC, gs.linkedAt ASC, gs.id ASC
            """)
    List<GuardianAccountRecipient> findActiveGuardianAccountsByStudentIds(@Param("academyId") Long academyId,
            @Param("studentIds") List<Long> studentIds);

    /**
     * 퇴원 학생의 보호자 연결을 지운다(개인정보 파기, Ruling 610) — 학생 행은 익명화돼 남지만 연결 행이 남으면
     * {@code 보호자(이름·전화) → guardian_student → student → run_rider → stop(주소·좌표)} 로 집 주소가 복원된다. 잃는 것은
     * "누구의 보호자였나" 뿐이다. 보호자 행 자체는 지우지 않는다(보존 기간은 열린 항목).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "보존 정리 배치(Ruling 610) — 학생 id 는 전 학원의 파기 대상 조회가 골라낸 값이고, "
            + "부르는 주체가 스케줄러라 요청 주체의 소속이 부재")
    @Query("DELETE FROM GuardianStudent gs WHERE gs.studentId IN :studentIds")
    int deleteAllByStudentIds(@Param("studentIds") Collection<Long> studentIds);
}
