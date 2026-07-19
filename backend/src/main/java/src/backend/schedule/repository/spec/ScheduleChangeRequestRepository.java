package src.backend.schedule.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.schedule.entity.ScheduleChangeRequest;

public interface ScheduleChangeRequestRepository extends JpaRepository<ScheduleChangeRequest, Long> {

    List<ScheduleChangeRequest> findByStudentIdInOrderByRequestedDateDesc(List<Long> studentIds);

    List<ScheduleChangeRequest> findByTenantIdOrderByRequestedDateDesc(Long tenantId);
}
