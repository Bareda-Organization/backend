package src.backend.rideevent.repository.spec;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.rideevent.entity.RideEvent;

/**
 * 승하차 기록 저장소.
 * 하나의 기록을 4개 역할이 각자 권한 범위(학생 본인 / 학부모 자녀 / 기사 담당버스 / 관리자 테넌트)에서
 * 조회하므로, 각 범위에 맞는 기간 조회 메서드를 둔다.
 */
public interface RideEventRepository extends JpaRepository<RideEvent, Long> {

    List<RideEvent> findByStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long studentId, LocalDateTime from, LocalDateTime to);

    List<RideEvent> findByStudentIdInAndOccurredAtBetweenOrderByOccurredAtAsc(
            List<Long> studentIds, LocalDateTime from, LocalDateTime to);

    List<RideEvent> findByBusIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long busId, LocalDateTime from, LocalDateTime to);

    List<RideEvent> findByTenantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long tenantId, LocalDateTime from, LocalDateTime to);

    List<RideEvent> findByTenantIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long tenantId, Long studentId, LocalDateTime from, LocalDateTime to);
}
