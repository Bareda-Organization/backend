package src.backend.account.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.account.dto.ReapplyRequestPayload;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 거절 뒤 재신청이 대기 요청 하나만 쌓는다 — 계정당 대기 가입 요청은 DB 유일 인덱스({@code uk_signup_request_account_pending})가
 * 하나로 묶는다. 이미 대기 요청이 있는 계정의 재신청은 그 위반이 500 으로 새지 않고, 대기 중이면 재신청할 수 없다는 기존 답
 * ({@code 409 REAPPLY_NOT_ALLOWED})과 같은 오류로 옮겨진다.
 */
@SpringBootTest
@Transactional
class SignupReapplyPendingTest {

    private static final String ACADEMY_ID = "1";

    @Autowired
    private SignupCommandService signupCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 대기_요청이_남은_거절_계정의_재신청은_409_REAPPLY_NOT_ALLOWED_다() {
        long accountId = 계정을_심는다("reapply-stale", "rejected");
        jdbcTemplate.update("INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, "
                + "requested_at) VALUES (?, 1, 'parent', 'staff', 'pending', now())", accountId);

        assertThatThrownBy(() -> signupCommandService.reapply(accountId, new ReapplyRequestPayload(ACADEMY_ID)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REAPPLY_NOT_ALLOWED));
    }

    @Test
    void 재신청을_연달아_두_번_보내면_둘째는_409_REAPPLY_NOT_ALLOWED_이고_대기_요청은_하나다() {
        long accountId = 계정을_심는다("reapply-twice", "rejected");

        signupCommandService.reapply(accountId, new ReapplyRequestPayload(ACADEMY_ID));

        assertThatThrownBy(() -> signupCommandService.reapply(accountId, new ReapplyRequestPayload(ACADEMY_ID)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REAPPLY_NOT_ALLOWED));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM signup_request WHERE account_id = ? AND status = 'pending'", Integer.class,
                accountId)).isEqualTo(1);
    }

    private long 계정을_심는다(String loginId, String status) {
        return jdbcTemplate.queryForObject("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, "
                + "status) VALUES (1, ?, 'x', '재신청', '010-0000-0000', 'parent', ?) RETURNING id", Long.class, loginId,
                status);
    }
}
