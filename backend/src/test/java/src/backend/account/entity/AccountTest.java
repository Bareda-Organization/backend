package src.backend.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@link Account#assertNotBlocked()} 단위 테스트 — 로그인 컨트롤러(Task 4)가 아직 없는 시점에도
 * blocked 판정 로직 자체를 검증한다(Task 2 브리프 §2.3). DB·Spring 컨텍스트 없이 순수 도메인
 * 규칙만 확인하므로, blocked·rejected·active 상태는 아직 없는 전이 메서드 대신
 * {@link ReflectionTestUtils}로 직접 주입한다.
 */
class AccountTest {

    @Test
    void blocked_계정_로그인은_403_AUTH_ACCOUNT_BLOCKED_이다() {
        Account account = Account.forSignup(1L, "login1", "hash", "이름", "010-0000-0001", null, Role.PARENT);
        ReflectionTestUtils.setField(account, "status", AccountStatus.BLOCKED);

        assertThatThrownBy(account::assertNotBlocked)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_ACCOUNT_BLOCKED));

        assertThat(ErrorCode.AUTH_ACCOUNT_BLOCKED.getStatus().value())
                .as("자격 오류(401)로 응답하면 안 된다 — 재시도가 실패 카운터를 다시 올린다")
                .isEqualTo(403);
    }

    @Test
    void pending_rejected_active_계정은_로그인이_막히지_않는다() {
        Account pending = Account.forSignup(1L, "login2", "hash", "이름", "010-0000-0002", null, Role.PARENT);
        pending.assertNotBlocked(); // forSignup 직후 기본값이 PENDING

        Account rejected = Account.forSignup(1L, "login3", "hash", "이름", "010-0000-0003", null, Role.PARENT);
        ReflectionTestUtils.setField(rejected, "status", AccountStatus.REJECTED);
        rejected.assertNotBlocked();

        Account active = Account.forSignup(1L, "login4", "hash", "이름", "010-0000-0004", null, Role.PARENT);
        ReflectionTestUtils.setField(active, "status", AccountStatus.ACTIVE);
        active.assertNotBlocked();
    }
}
