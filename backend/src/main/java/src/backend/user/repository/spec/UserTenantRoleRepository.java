package src.backend.user.repository.spec;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.user.entity.Role;
import src.backend.user.entity.UserTenantRole;

/**
 * 사용자–학원–역할 연결 저장소. 인증 시 이 사용자가 어느 학원에서 어떤 역할인지 조회한다.
 */
public interface UserTenantRoleRepository extends JpaRepository<UserTenantRole, Long> {

    List<UserTenantRole> findByUserId(Long userId);

    /** 학원 구성원 목록(관리자 조회). */
    List<UserTenantRole> findByTenantId(Long tenantId);

    /** 학원 내 특정 역할 구성원(예: 기사 배차 드롭다운). */
    List<UserTenantRole> findByTenantIdAndRole(Long tenantId, Role role);

    /**
     * "이 사람이 이 학원 구성원인가"를 한 번에 본다 — 구성원 상세·수정·해제의 유일한 격리 지점.
     * 사용자부터 찾고 나중에 학원을 검사하면 검사 한 번을 빠뜨리는 순간 전 학원 계정 조회기가 된다.
     */
    Optional<UserTenantRole> findByUserIdAndTenantId(Long userId, Long tenantId);
}
