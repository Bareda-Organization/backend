package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;

/**
 * Phase 4 목표 2 — 발송을 <b>항상 실패</b>시킨 컨텍스트에서 상태 변경이 살아남고, 재시도할 대상이
 * DB 에 남는지 본다(TECH_DECISIONS §7.5 "외부 푸시 발송은 트랜잭션 안에서 하지 않는다").
 *
 * <p>{@code app.push.sender} 로 구현체를 갈아끼운다 — 이 값이 {@code logging} 이 아니면 기본 구현
 * ({@code LoggingPushSender})의 {@code @ConditionalOnProperty} 가 꺼지고 아래 {@link 항상_실패하는_발송}
 * 만 남는다. 즉 "구현체 선택은 설정 한 곳에서 한다"(ARCHITECTURE §3.2.1)가 실제로 성립하는지도
 * 이 테스트가 함께 고정한다.
 *
 * <p>계정 상태·요청 상태를 <b>함께</b> 보는 이유는 응답 코드만으로는 갈리지 않기 때문이다 — 발송을
 * 같은 트랜잭션에 넣은 구현은 승인 자체를 롤백시키는데, 그 예외가 잡히면 응답은 여전히 200 이다.
 */
@SpringBootTest(properties = "app.push.sender=always-failing")
@AutoConfigureMockMvc
class SignupDecidedDispatchFailureTest {

    private static final String LOGIN_ID_PREFIX = "p4t1fail";

    /** 발송 구현체가 던지는 사유 — {@code fail_reason} 에 옮겨졌는지 대조할 값이다. */
    private static final String 발송_실패_사유 = "P4T1 강제 발송 실패";

    @TestConfiguration
    static class 항상_실패하는_발송 {

        /** 실 채널 장애를 대신한다 — 던지는 것 말고는 아무 일도 하지 않는다. */
        @Bean
        PushSender alwaysFailingPushSender() {
            return (PushMessage message) -> {
                throw new IllegalStateException(발송_실패_사유);
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private SignupDecidedFixture fixture;

    @BeforeEach
    void 대기_요청을_만든다() {
        fixture = new SignupDecidedFixture(jdbcTemplate);
        fixture.뒷정리한다(LOGIN_ID_PREFIX);
    }

    @AfterEach
    void 뒷정리한다() {
        new SignupDecidedFixture(jdbcTemplate).뒷정리한다(LOGIN_ID_PREFIX);
    }

    @Test
    void 발송이_실패해도_가입_승인_트랜잭션은_롤백되지_않는다() throws Exception {
        long requestId = 학부모_대기_요청("ok", "010-4200-0001");

        수락한다(requestId).andExpect(status().isOk());

        assertThat(요청_상태(requestId))
                .as("발송 실패가 요청 마감까지 되돌리면 관계자는 같은 건을 다시 처리해야 한다")
                .isEqualTo("accepted");
        assertThat(계정_상태(LOGIN_ID_PREFIX + "ok"))
                .as("승인했는데 계정이 pending 으로 남으면 사용자는 승인된 줄 모르고 대기 화면에 머문다")
                .isEqualTo("active");
    }

    @Test
    void 발송이_실패하면_알림_행이_pending_으로_남고_fail_reason_이_기록된다() throws Exception {
        long requestId = 학부모_대기_요청("row", "010-4200-0002");

        수락한다(requestId).andExpect(status().isOk());

        List<Map<String, Object>> rows = 알림_행(LOGIN_ID_PREFIX + "row");
        assertThat(rows)
                .as("행이 없으면 재시도할 대상 자체가 부재해 그 알림은 영구 유실이다")
                .hasSize(1);
        assertThat(rows.get(0).get("push_state"))
                .as("실패는 pending 으로 남아야 워커가 다시 집는다 — sent 면 유실, failed 면 조기 포기다")
                .isEqualTo("pending");
        assertThat((String) rows.get(0).get("fail_reason"))
                .as("사유가 비면 운영자가 채널 장애인지 토큰 만료인지 가릴 수단이 부재하다")
                .contains(발송_실패_사유);
        assertThat(rows.get(0).get("sent_at"))
                .as("보내지 못했는데 sent_at 이 차 있으면 발송 사실의 근거가 거짓이 된다")
                .isNull();
    }

    private long 학부모_대기_요청(String suffix, String phone) {
        return fixture.대기_요청을_만든다(SignupDecidedFixture.SEED_ACADEMY_A, LOGIN_ID_PREFIX + suffix,
                "P4T1실패" + suffix, phone, "parent", "staff");
    }

    private org.springframework.test.web.servlet.ResultActions 수락한다(long requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/signup-requests/" + requestId + "/decide")
                .header("Authorization", "Bearer " + tokenProvider.createAccessToken(
                        SignupDecidedFixture.SEED_STAFF_ACCOUNT, SignupDecidedFixture.SEED_ACADEMY_A,
                        Role.STAFF, AccountStatus.ACTIVE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accept": true, "link": {"student_ids": [%d]}}
                        """.formatted(SignupDecidedFixture.SEED_STUDENT_A1)));
    }

    private String 요청_상태(long requestId) {
        return jdbcTemplate.queryForObject("SELECT status FROM signup_request WHERE id = ?", String.class,
                requestId);
    }

    private String 계정_상태(String loginId) {
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE login_id = ?", String.class,
                loginId);
    }

    private List<Map<String, Object>> 알림_행(String loginId) {
        return jdbcTemplate.queryForList("""
                SELECT push_state, fail_reason, sent_at, push_attempts
                  FROM notification_log
                 WHERE recipient_account_id = ? AND type = 'signup_decided'
                """, fixture.계정_식별자(loginId));
    }
}
