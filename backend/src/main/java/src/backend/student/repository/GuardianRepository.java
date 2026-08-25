package src.backend.student.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Guardian;

/** {@link Guardian} 영속성 접근. */
public interface GuardianRepository extends JpaRepository<Guardian, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 보호자 쪽에 조건을 더해도 좁혀지는 것이 부재")
    Optional<Guardian> findByAccountId(Long accountId);
}
