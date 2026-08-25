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
}
