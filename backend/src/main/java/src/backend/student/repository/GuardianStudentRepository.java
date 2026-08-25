package src.backend.student.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.GuardianStudent;

public interface GuardianStudentRepository extends JpaRepository<GuardianStudent, Long> {
    /** 해지되지 않은(unlinked_at IS NULL) 연결만 센다 — §2.10 linked_student_count. */
    @AcademyScopeExempt(reason = "§2.10 본인 연결 학생 수 — guardian_student 는 guardian 부모 경유라 보호자가 곧 학원 범위. "
            + "호출부가 토큰의 accountId 로 찾은 Guardian 의 id 만 넘긴다는 전제 — 요청 파라미터의 guardianId 를 "
            + "넘기면 타 학원 보호자의 연결 수가 새어 이 예외가 우회로가 된다")
    long countByGuardianIdAndUnlinkedAtIsNull(Long guardianId);
}
