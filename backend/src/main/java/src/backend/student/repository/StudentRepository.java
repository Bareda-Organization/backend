package src.backend.student.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Student;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByAccountId(Long accountId);
}
