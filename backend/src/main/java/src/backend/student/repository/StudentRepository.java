package src.backend.student.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
