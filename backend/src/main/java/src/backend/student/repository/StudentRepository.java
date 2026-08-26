package src.backend.student.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Student;

/** {@link Student} 영속성 접근. */
public interface StudentRepository extends JpaRepository<Student, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 학생 쪽에 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<Student> findByAccountId(Long accountId);

    /**
     * 한 학원의 재학생 목록(STU-01) — 학원 조건이 <b>쿼리에 고정</b>돼 호출부가 빼먹을 자리가 부재하다
     * (ARCHITECTURE §6.1 이 지목한 목록 조회 사고 지점).
     *
     * <p>조건·정렬이 {@code ix_student_academy_name}(부분 인덱스, {@code WHERE deleted_at IS NULL})
     * 과 같은 형태라 그 인덱스를 그대로 탄다(ERD §5.3).
     */
    List<Student> findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc(Long academyId);

    /**
     * 전 학원 재학생 목록 — 메인 관리자가 학원을 지정하지 않은 경우 하나뿐인 경로다
     * (ARCHITECTURE §6.2 격리 예외).
     *
     * <p>학원 조건은 없어도 {@code deletedAt IS NULL} 은 <b>있어야 한다</b>. 위 학원별 조회가 퇴원생을
     * 거르는데 이쪽만 거르지 않으면, 같은 화면이 학원을 고르느냐에 따라 퇴원생이 나왔다 사라진다
     * (ERD §7.1 soft delete — 오늘 명단은 유지하되 목록에서는 제외).
     */
    @AcademyScopeExempt(reason = "ARCHITECTURE §6.2 격리 예외 — 메인 관리자의 전 학원 조회라 좁힐 학원이 부재. "
            + "호출부가 AcademyScope.resolveListScope 의 빈 Optional(= 플랫폼 범위 + 학원 미지정) 에서만 "
            + "부른다는 전제 — 학원이 지정된 경로에서 부르면 격리가 통째로 빠진다")
    List<Student> findAllByDeletedAtIsNullOrderByNameAsc();

    /**
     * 가입 승인이 연결할 학생 1건(AUTH-11 · API_SPEC §5.2) — 학원 조건이 <b>쿼리에 고정</b>돼 있다.
     *
     * <p>{@code findById} 로 꺼내 뒤에서 대조하지 않는 이유는, 이 경로에서 학원이 어긋난 결과가
     * {@code 403} 이 아니라 {@code 404 STUDENT_NOT_FOUND} 여야 하기 때문이다(§5.2) — 조건을 쿼리에
     * 넣으면 "없음" 과 "남의 학원" 이 같은 빈 결과가 되어 존재 여부가 응답에서 사라진다.
     *
     * <p>퇴원생({@code deleted_at})은 대상 밖이다 — 명단에서 빠진 학생에 새 학부모를 잇는 것은
     * 연결이 아니라 되살리기다.
     */
    Optional<Student> findByIdAndAcademyIdAndDeletedAtIsNull(Long id, Long academyId);

    /**
     * 관계자 웹의 학생 목록·검색(STU-01, API_SPEC §5.11) — 학원과 퇴원 여부가 <b>쿼리에 고정</b>돼
     * 호출부가 빼먹을 자리가 부재하다.
     *
     * <p>검색어를 {@code IS NULL} 로 가르지 않고 <b>빈 문자열이 전건과 같아지는 형태</b>로 쓴다 —
     * {@code :q IS NULL} 은 PostgreSQL 이 파라미터 타입을 정하지 못해 조회 자체가 실패하는 자리다.
     * 값을 주지 않은 요청을 빈 문자열로 바꾸는 것은 호출부의 몫이다.
     *
     * <p>정렬은 {@link Pageable} 이 붙인다 — 이 쿼리에 {@code ORDER BY} 를 박으면 정렬 파라미터
     * ({@code §1.8})가 무시된 채로도 결과가 그럴듯해 아무도 알아채지 못한다.
     */
    @Query("""
            SELECT s FROM Student s
            WHERE s.academyId = :academyId
              AND s.deletedAt IS NULL
              AND LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<Student> searchByAcademyId(@Param("academyId") Long academyId, @Param("q") String q, Pageable pageable);
}
