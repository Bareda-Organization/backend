package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * STOMP 구독 인가가 부르는 두 포트({@link StudentChannelAccess} · {@link RunChannelAccess})의 구현은 토큰의 계정 id · 역할로 찾은 행(보호자 · 배치)을 기준으로 판정한다 —
 * 학원은 그 행이 가진 학원이다(보호자 행의 학원은 계정과 같다는 것을 복합 외래 키가 보장한다). 구독자가 남의 학원 학생 · 회차 채널을 얻지 못한다. 시드 계정은 로그인 아이디({@link SeedFixtures})로 찾는다: 학생 1 의 보호자 = parentA1,
 * 학생 3 은 그 보호자와 연결이 없는 학생, 학생 본인 계정 = studentA4, 회차 1 의 기사 = driverA1. 학원 B 의 계정은 parentB1 · driverB1 이다.
 */
@SpringBootTest
@Transactional
class ChannelAccessPortsTest {

    private static final long ACADEMY_A = Long.parseLong(SeedFixtures.ACADEMY_A_ID);
    private static final long ACADEMY_B = Long.parseLong(SeedFixtures.ACADEMY_B_ID);
    private static final long SIBLING_1 = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);
    private static final long UNLINKED_STUDENT = Long.parseLong(SeedFixtures.STUDENT_UNLINKED_ID);
    private static final long RUN = Long.parseLong(SeedFixtures.RUN_IDLE_ID);

    @Autowired
    private StudentChannelAccess studentChannel;

    @Autowired
    private RunChannelAccess runChannel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("연결된 보호자와 학생 본인은 자기 채널을 구독한다")
    void 보호자와_본인은_통과한다() {
        long ownStudent = studentIdOf(SeedFixtures.STUDENT_A4_LOGIN_ID);

        assertThatCode(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.PARENT_A1_LOGIN_ID, ACADEMY_A, Role.PARENT), SIBLING_1))
                .doesNotThrowAnyException();
        assertThatCode(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.STUDENT_A4_LOGIN_ID, ACADEMY_A, Role.STUDENT), ownStudent))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른 학원 보호자 · 연결 없는 보호자 · 남의 학생 채널을 지목한 학생 · 학생이 아닌 역할은 FORBIDDEN 이다")
    void 남의_채널은_FORBIDDEN() {
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.PARENT_B1_LOGIN_ID, ACADEMY_B, Role.PARENT), SIBLING_1));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.PARENT_A1_LOGIN_ID, ACADEMY_A, Role.PARENT), UNLINKED_STUDENT));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.STUDENT_A4_LOGIN_ID, ACADEMY_A, Role.STUDENT), SIBLING_1));
        assertForbidden(() -> studentChannel.assertMayFollowStudent(user(SeedFixtures.DRIVER_A1_LOGIN_ID, ACADEMY_A, Role.DRIVER), SIBLING_1));
    }

    @Test
    @DisplayName("회차에 배치된 기사는 매니저 채널을 구독하고, 배치되지 않은 기사 · 다른 학원 기사 · 보호자는 FORBIDDEN 이다")
    void 배치된_기사만_회차_채널을_구독한다() {
        assertThatCode(() -> runChannel.assertMayFollowRun(user(SeedFixtures.DRIVER_A1_LOGIN_ID, ACADEMY_A, Role.DRIVER), RUN))
                .doesNotThrowAnyException();
        assertForbidden(() -> runChannel.assertMayFollowRun(user(SeedFixtures.DRIVER_A2_LOGIN_ID, ACADEMY_A, Role.DRIVER), RUN));
        assertForbidden(() -> runChannel.assertMayFollowRun(user(SeedFixtures.DRIVER_B1_LOGIN_ID, ACADEMY_B, Role.DRIVER), RUN));
        assertForbidden(() -> runChannel.assertMayFollowRun(user(SeedFixtures.PARENT_A1_LOGIN_ID, ACADEMY_A, Role.PARENT), RUN));
    }

    private AuthUser user(String loginId, long academyId, Role role) {
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        return new AuthUser(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private long studentIdOf(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM student WHERE account_id = (SELECT id FROM account WHERE login_id = ?)", Long.class, loginId);
    }

    private static void assertForbidden(Runnable judgment) {
        assertThatThrownBy(judgment::run).isInstanceOfSatisfying(BusinessException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
