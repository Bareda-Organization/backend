package src.backend.manager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.manager.entity.Manager;

public interface ManagerRepository extends JpaRepository<Manager, Long> {
    Optional<Manager> findByAccountId(Long accountId);
}
