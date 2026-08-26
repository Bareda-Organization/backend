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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * Phase 4 목표 1 — <b>가입 결정 경로 4개 전부</b>에서 신청자 앞으로 {@code signup_decided} 행이
 * 적재되고 {@code sent} 로 전이하는지 본다(API_SPEC §5.2·§6.5 × 수락·거절).
 *
 * <p>네 경로를 한 클래스에 둔 이유는 <b>빠뜨림이 이 축의 사고 형태</b>이기 때문이다. 한 경로에만
 * 발행을 걸어도 나머지 세 경로의 {@code decide} 응답은 그대로 200 이라, 경로별로 클래스를 나누면
 * "세 개 중 하나가 없다" 를 아무도 보지 못한다. 특히 <b>거절</b>은 통지가 빠져도 아무 에러가 남지
 * 않고 거절된 사람만 대기 화면에 영원히 남는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 즉시 발송이 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 라, 테스트가 트랜잭션을 열고 있으면 커밋이 일어나지 않아 <b>발송 단계 자체가 실행되지 않는다.</b>
 * 대신 {@link SignupDecidedFixture} 가 만든 행을 {@link #뒷정리한다()} 가 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupDecidedOutboxTest {

    private static final String LOGIN_ID_PREFIX = "p4t1ntf";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private SignupDecidedFixture fixture;

    private long 새_학원;

    @BeforeEach
    void 대기_요청_네_건을_만든다() {
        fixture = new SignupDecidedFixture(jdbcTemplate);
        fixture.뒷정리한다(LOGIN_ID_PREFIX);
        새_학원 = fixture.학원을_만든다();
    }

    @AfterEach
    void 뒷정리한다() {
        new SignupDecidedFixture(jdbcTemplate).뒷정리한다(LOGIN_ID_PREFIX);
    }

    @Test
    void 관계자가_가입을_승인하면_신청자에게_signup_decided_알림이_sent_로_적재된다() throws Exception {
        long requestId = fixture.대기_요청을_만든다(SignupDecidedFixture.SEED_ACADEMY_A,
                LOGIN_ID_PREFIX + "pok", "P4T1수락학부모", "010-4100-0001", "parent", "staff");

        mockMvc.perform(post("/api/v1/staff/signup-requests/" + requestId + "/decide")
                        .header("Authorization", 관계자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accept": true, "link": {"student_ids": [%d]}}
                                """.formatted(SignupDecidedFixture.SEED_STUDENT_A1)))
                .andExpect(status().isOk());

        발송된_가입_결정_알림이_한_건이다(LOGIN_ID_PREFIX + "pok");
    }

    @Test
    void 관계자가_가입을_거절해도_신청자에게_signup_decided_알림이_적재된다() throws Exception {
        long requestId = fixture.대기_요청을_만든다(SignupDecidedFixture.SEED_ACADEMY_A,
                LOGIN_ID_PREFIX + "pno", "P4T1거절학부모", "010-4100-0002", "parent", "staff");

        mockMvc.perform(post("/api/v1/staff/signup-requests/" + requestId + "/decide")
                        .header("Authorization", 관계자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": false, \"reject_reason\": \"제출 서류가 부족합니다\"}"))
                .andExpect(status().isOk());

        발송된_가입_결정_알림이_한_건이다(LOGIN_ID_PREFIX + "pno");
    }

    @Test
    void 메인_관리자가_관계자_가입을_승인하면_signup_decided_알림이_적재된다() throws Exception {
        long requestId = fixture.대기_요청을_만든다(새_학원,
                LOGIN_ID_PREFIX + "sok", "P4T1수락관계자", "010-4100-0003", "staff", "system_admin");

        mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + requestId + "/decide")
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true}"))
                .andExpect(status().isOk());

        발송된_가입_결정_알림이_한_건이다(LOGIN_ID_PREFIX + "sok");
    }

    @Test
    void 메인_관리자가_관계자_가입을_거절해도_signup_decided_알림이_적재된다() throws Exception {
        long requestId = fixture.대기_요청을_만든다(새_학원,
                LOGIN_ID_PREFIX + "sno", "P4T1거절관계자", "010-4100-0004", "staff", "system_admin");

        mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + requestId + "/decide")
                        .header("Authorization", 메인관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": false, \"reject_reason\": \"정원이 이미 찼습니다\"}"))
                .andExpect(status().isOk());

        발송된_가입_결정_알림이_한_건이다(LOGIN_ID_PREFIX + "sno");
    }

    /**
     * 신청자 앞으로 {@code signup_decided} 행이 <b>정확히 1건</b> 있고 {@code sent} 로 전이했는지 본다.
     *
     * <p>개수를 함께 세는 이유는 즉시 발송과 워커가 각자 적재하는 구현도 "행이 있다" 만으로는
     * 통과하기 때문이다. {@code sent_at} 을 따로 보는 이유는 상태만 옮기고 시각을 비워 두면
     * 관계자 로그(NTF-11)에서 발송 시점을 되짚을 수단이 사라지기 때문이다.
     */
    private void 발송된_가입_결정_알림이_한_건이다(String loginId) {
        long accountId = fixture.계정_식별자(loginId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT push_state, sent_at, dedup_key, title, body, recipient_role
                  FROM notification_log
                 WHERE recipient_account_id = ? AND type = 'signup_decided'
                """, accountId);

        assertThat(rows)
                .as("가입 결정 알림이 적재되지 않았다 — 이 경로만 이벤트 발행이 빠져도 decide 응답은 200 이다")
                .hasSize(1);
        assertThat(rows.get(0).get("push_state"))
                .as("커밋 직후 즉시 발송(AFTER_COMMIT)이 성공하면 sent 여야 한다")
                .isEqualTo("sent");
        assertThat(rows.get(0).get("sent_at"))
                .as("sent 인데 sent_at 이 비어 있으면 발송 시점을 되짚을 수단이 부재하다")
                .isNotNull();
        assertThat((String) rows.get(0).get("dedup_key"))
                .as("dedup_key 는 ERD 의 {event}:{run_id}:{대상}:{판정 시각} 형태여야 한다")
                .startsWith("signup_decided:na:" + accountId + ":");
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SignupDecidedFixture.SEED_STAFF_ACCOUNT,
                SignupDecidedFixture.SEED_ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SignupDecidedFixture.SEED_SYSTEM_ADMIN_ACCOUNT,
                null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
