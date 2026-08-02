package src.backend.schedule.repository.spec;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.schedule.entity.LocationChangeRequest;

public interface LocationChangeRequestRepository extends JpaRepository<LocationChangeRequest, Long> {

    /** 학부모 이력 — 자녀 전체의 신청을 최신순으로. */
    List<LocationChangeRequest> findByStudentIdInOrderByCreatedAtDesc(List<Long> studentIds);

    /** 관리자 이력 — 학원 전체 신청을 최신순으로(반려·차단 포함, 감사 목적). */
    List<LocationChangeRequest> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
