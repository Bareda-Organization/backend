package testsupport.timeaudit;

import org.springframework.data.jpa.repository.JpaRepository;

/** 테스트 전용 — AuditingProbeEntity 저장·조회. */
public interface AuditingProbeEntityRepository extends JpaRepository<AuditingProbeEntity, Long> {
}
