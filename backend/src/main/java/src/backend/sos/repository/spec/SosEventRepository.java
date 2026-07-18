package src.backend.sos.repository.spec;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;

public interface SosEventRepository extends JpaRepository<SosEvent, Long> {

    List<SosEvent> findByStudentIdOrderByOccurredAtDesc(Long studentId);

    List<SosEvent> findByStudentIdInOrderByOccurredAtDesc(List<Long> studentIds);

    List<SosEvent> findByTenantIdOrderByOccurredAtDesc(Long tenantId);

    /** 에스컬레이션 스케줄러용 — 아직 미확인(OPEN)이며 발신 후 임계시간이 지난 이벤트. */
    List<SosEvent> findByStatusAndOccurredAtBefore(SosStatus status, LocalDateTime cutoff);
}
