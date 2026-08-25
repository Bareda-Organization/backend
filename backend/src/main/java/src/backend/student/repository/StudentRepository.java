package src.backend.student.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Student;

/** {@link Student} 영속성 접근. */
public interface StudentRepository extends JpaRepository<Student, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 학생 쪽에 조건을 더해도 좁혀지는 것이 부재")
    Optional<Student> findByAccountId(Long accountId);

    /**
     * 한 학원의 재학생 목록(STU-01) — 학원 조건이 <b>쿼리에 고정</b>돼 호출부가 빼먹을 자리가 부재하다
     * (ARCHITECTURE §6.1 이 지목한 목록 조회 사고 지점).
     *
     * <p>조건·정렬이 {@code ix_student_academy_name}(부분 인덱스, {@code WHERE deleted_at IS NULL})
     * 과 같은 형태라 그 인덱스를 그대로 탄다(ERD §5.3).
     */
    List<Student> findAllByAcademyIdAndDeletedAtIsNullOrderByNameAsc(Long academyId);
}
