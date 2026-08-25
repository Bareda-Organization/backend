package src.backend.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.enums.AccountStatus;
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
        // forSignup 직후 기본값이 PENDING

        Account rejected = Account.forSignup(1L, "login3", "hash", "이름", "010-0000-0003", null, Role.PARENT);
        ReflectionTestUtils.setField(rejected, "status", AccountStatus.REJECTED);

        Account active = Account.forSignup(1L, "login4", "hash", "이름", "010-0000-0004", null, Role.PARENT);
        ReflectionTestUtils.setField(active, "status", AccountStatus.ACTIVE);

        // 암묵적 무예외(호출만 하고 끝)로는 assertNotBlocked 가 조용히 삼킨 예외와 구별되지 않는다 —
        // 명시적으로 "예외가 없다" 를 단언해야 한다(리뷰 라운드 1 Minor #2).
        assertThatCode(pending::assertNotBlocked).doesNotThrowAnyException();
        assertThatCode(rejected::assertNotBlocked).doesNotThrowAnyException();
        assertThatCode(active::assertNotBlocked).doesNotThrowAnyException();
    }

    @Test
    void active_계정의_재신청은_409_REAPPLY_NOT_ALLOWED_이다() {
        // AccountStatusGateInterceptor 는 active 계정을 그대로 통과시켜(재신청 API 에 게이트를 걸지 않음),
        // 이 엔티티 메서드의 상태 가드가 active 계정의 재신청을 막는 유일한 방어선이다.
        Account active = Account.forSignup(1L, "login5", "hash", "이름", "010-0000-0005", null, Role.PARENT);
        ReflectionTestUtils.setField(active, "status", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> active.reapply(2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REAPPLY_NOT_ALLOWED));

        assertThat(ErrorCode.REAPPLY_NOT_ALLOWED.getStatus().value()).isEqualTo(409);
    }
}
