package src.backend.student.repository.spec;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.student.entity.StudentGuardian;

/**
 * 학생–보호자 연결 저장소.
 * - findByGuardianId: 학부모 계정 → 연결된 자녀 목록(형제자매 포함)
 * - findWithGuardianByStudentIdIn: 명단 화면용 일괄 조회
 */
public interface StudentGuardianRepository extends JpaRepository<StudentGuardian, Long> {

    List<StudentGuardian> findByGuardianId(Long guardianId);

    List<StudentGuardian> findByStudentId(Long studentId);

    /** 연결 한 건 — 중복 연결(409)과 해제 대상 확인에 쓴다. unique(student_id, guardian_id) 라 최대 1건이다. */
    Optional<StudentGuardian> findByStudentIdAndGuardianId(Long studentId, Long guardianId);

    /** guardian 이 LAZY 라 fetch join 없이는 학생 수만큼 추가 쿼리가 난다. */
    @Query("select sg from StudentGuardian sg join fetch sg.guardian where sg.student.id in :studentIds")
    List<StudentGuardian> findWithGuardianByStudentIdIn(@Param("studentIds") Collection<Long> studentIds);
}
