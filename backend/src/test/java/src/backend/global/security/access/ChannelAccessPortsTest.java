package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * STOMP 구독 인가가 부르는 두 포트({@link StudentChannelAccess} · {@link RunChannelAccess})의 구현이 호출자 토큰의 학원 · 역할로 판정한다 —
 * 구독자가 남의 학원 학생 · 회차 채널을 얻지 못한다. 시드: 학생 1 의 보호자 = 계정 5(학원 1), 학생 4 의 본인 계정 = 10, 회차 1 의 기사 = 계정 13(학원 1).
 */
@SpringBootTest
@Transactional
class ChannelAccessPortsTest {

    @Autowired
    private StudentChannelAccess studentChannel;

    @Autowired
    private RunChannelAccess runChannel;

    @Test
    @DisplayName("연결된 보호자와 학생 본인은 자기 채널을 구독한다")
    void 보호자와_본인은_통과한다() {
        assertThatCode(() -> studentChannel.assertMayFollowStudent(user(5L, 1L, Role.PARENT), 1L)).doesNotThrowAnyException();
        assertThatCode(() -> studentChannel.assertMayFollowStudent(user(10L, 1L, Role.STUDENT), 4L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른 학원 보호자 · 연결 없는 보호자 · 남의 학생 채널을 지목한 학생 · 학생이 아닌 역할은 FORBIDDEN 이다")
    void 남의_채널은_FORBIDDEN() {
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(9L, 2L, Role.PARENT), 1L));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(5L, 1L, Role.PARENT), 3L));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(10L, 1L, Role.STUDENT), 1L));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(13L, 1L, Role.DRIVER), 1L));
    }

    @Test
    @DisplayName("회차에 배치된 기사는 매니저 채널을 구독하고, 배치되지 않은 기사 · 보호자는 FORBIDDEN 이다")
    void 배치된_기사만_회차_채널을_구독한다() {
        assertThatCode(() -> runChannel.assertMayFollowRun(user(13L, 1L, Role.DRIVER), 1L)).doesNotThrowAnyException();
        assertForbidden(() -> runChannel.assertMayFollowRun(user(999_999L, 1L, Role.DRIVER), 1L));
        assertForbidden(() -> runChannel.assertMayFollowRun(user(5L, 1L, Role.PARENT), 1L));
    }

    private static AuthUser user(long accountId, long academyId, Role role) {
        return new AuthUser(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private static void assertForbidden(Runnable judgment) {
        assertThatThrownBy(judgment::run).isInstanceOfSatisfying(BusinessException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
