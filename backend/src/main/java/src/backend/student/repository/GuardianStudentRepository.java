package src.backend.student.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.GuardianStudent;

public interface GuardianStudentRepository extends JpaRepository<GuardianStudent, Long> {
    /** 해지되지 않은(unlinked_at IS NULL) 연결만 센다 — §2.10 linked_student_count. */
    long countByGuardianIdAndUnlinkedAtIsNull(Long guardianId);
}
