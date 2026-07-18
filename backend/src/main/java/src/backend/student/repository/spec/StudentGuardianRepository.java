package src.backend.student.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.StudentGuardian;

/**
 * 학생–보호자 연결 저장소.
 * - findByGuardianId: 학부모 계정 → 연결된 자녀 목록(형제자매 포함)
 */
public interface StudentGuardianRepository extends JpaRepository<StudentGuardian, Long> {

    List<StudentGuardian> findByGuardianId(Long guardianId);

    List<StudentGuardian> findByStudentId(Long studentId);
}
