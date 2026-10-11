package src.backend.run.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 삭제된 매니저는 배치 판정을 쓰는 모든 경로에서 {@code 403 FORBIDDEN} 이다 — Ruling 885 ⑥.
 *
 * <p>HTTP 로는 Ruling 880 의 인증 필터가 먼저 {@code 401} 로 막아 이 판정에 닿지 않는다 — 이 판정은 그 앞단이 뚫렸을 때를 받치는
 * 두 번째 방어선이라, 배치 판정 컴포넌트를 직접 부른다. 판정을 쓰는 경로 대표: 승하차·비상·변경 확인({@code assertAssignedDriverOrEscort}) ·
 * 지연 알림({@code assertAssignedEscort}) · 운행 시작·도착({@code assertAssignedDriver}) · 위치 송신({@code assertAssignedDriverExists}).
 * 시드: 회차 1 의 기사 = 계정 13(매니저 1) · 동승자 = 계정 17(매니저 3).
 */
@SpringBootTest
@Transactional
class RunAssignmentAccessDeletedManagerTest {

    private static final long RUN_ID = 1L;
    private static final AuthUser DRIVER = new AuthUser(13L, 1L, Role.DRIVER, AccountStatus.ACTIVE);
    private static final AuthUser ESCORT = new AuthUser(17L, 1L, Role.ESCORT, AccountStatus.ACTIVE);

    @Autowired
    private RunAssignmentAccess access;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 삭제되지_않은_배치_매니저는_모든_배치_판정을_통과한다() {
        assertThat(access.assertAssignedDriver(DRIVER, RUN_ID)).isNotNull();
        assertThat(access.assertAssignedEscort(ESCORT, RUN_ID)).isNotNull();
        assertThat(access.assertAssignedDriverOrEscort(DRIVER, RUN_ID)).isNotNull();
        assertThat(access.assertAssignedDriverOrEscort(ESCORT, RUN_ID)).isNotNull();
        access.assertAssignedDriverExists(DRIVER, RUN_ID);
    }

    @Test
    void 삭제된_매니저는_배치가_남아_있어도_배치_판정_네_갈래_모두_FORBIDDEN_이다() {
        jdbcTemplate.update("UPDATE manager SET deleted_at = now() WHERE account_id IN (13, 17)");
        entityManager.clear();

        assertForbidden(() -> access.assertAssignedDriver(DRIVER, RUN_ID));
        assertForbidden(() -> access.assertAssignedEscort(ESCORT, RUN_ID));
        assertForbidden(() -> access.assertAssignedDriverOrEscort(DRIVER, RUN_ID));
        assertForbidden(() -> access.assertAssignedDriverOrEscort(ESCORT, RUN_ID));
        assertForbidden(() -> access.assertAssignedDriverExists(DRIVER, RUN_ID));
    }

    private static void assertForbidden(Runnable judgment) {
        assertThatThrownBy(judgment::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
