package src.backend.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.SystemAdmin;

/** {@link SystemAdmin} 영속성 접근 — 메인 관리자 등록부라 학원 범위가 없다(ERD §6.1 밖). */
public interface SystemAdminRepository extends JpaRepository<SystemAdmin, Long> {
}
