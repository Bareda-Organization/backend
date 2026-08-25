package src.backend.manager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.manager.entity.Manager;

/** {@link Manager} 영속성 접근. */
public interface ManagerRepository extends JpaRepository<Manager, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 매니저 쪽에 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<Manager> findByAccountId(Long accountId);

    /**
     * 가입 승인이 연결할 매니저 1건(AUTH-11 · API_SPEC §5.2) — 학원과 <b>역할</b>로 함께 좁힌다.
     *
     * <p>역할을 조건에 넣는 이유는 {@code manager.role} 이 앱 권한을 결정하기 때문이다(C-06) —
     * 기사 계정을 동승자 레코드에 이으면 계정의 역할과 레코드의 역할이 갈리고, 그 계정은 승하차를
     * 기록할 수 있는지 없는지가 보는 곳마다 달라진다. 어긋난 지정은 {@code 404 MANAGER_NOT_FOUND} 로
     * 학생 쪽과 같은 형태로 답한다(§5.2).
     */
    Optional<Manager> findByIdAndAcademyIdAndRoleAndDeletedAtIsNull(Long id, Long academyId, ManagerRole role);
}
