package src.backend.student.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.student.entity.Guardian;

public interface GuardianRepository extends JpaRepository<Guardian, Long> {
    Optional<Guardian> findByAccountId(Long accountId);
}
