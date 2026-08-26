package src.backend.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 퇴원(STU-04)이 보호자 연결을 해제하는지 — {@code ERD §7.1} · UF-P-01 (Ruling 172).
 *
 * <p>읽는 쪽과 쓰는 쪽이 <b>다른 태스크에서</b> 만들어져 벌어진 자리다. 접근 판정
 * ({@code GuardianChildAccess})은 {@code unlinked_at IS NULL} 을 정확히 보는데 그 값을 채우는 코드가
 * 부재해, <b>퇴원한 자녀가 옛 보호자의 목록에 계속 남았다.</b> 어느 쪽 테스트도 이것을 보지 못한
 * 이유는 각자 자기 절반만 검사했기 때문이다 — 퇴원 시험은 {@code deleted_at} 만 보고, 자녀 목록
 * 시험은 {@code unlinked_at} 을 SQL 로 직접 채워 넣고 봤다.
 *
 * <p>그래서 이 클래스는 <b>두 API 를 잇는다</b> — 관계자 웹의 퇴원({@code DELETE /staff/students/{id}},
 * §5.11)으로 지우고 학부모 앱의 자녀 목록({@code GET /me/students}, §3.1)으로 확인한다. 한쪽만 보면
 * 같은 결함이 다시 생겨도 초록이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentWithdrawalUnlinkTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    /** 형제 S1·S2 의 보호자({@code parentA1}) — 한 자녀만 퇴원시켜 나머지가 남는지 함께 본다. */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);
    private static final long SIBLING_2_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_2_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 해제 시각이 <b>주입된 시계</b> 기준인지 보려면 고정이 필요하다(횡단 규칙 1). */
    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /** {@code StaffStudentControllerTest} 와 같은 형태 — 값 자체가 아니라 고정돼 있다는 사실이 검사 대상이다. */
    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2026-08-26T00:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    /**
     * 퇴원은 그 학생의 살아 있는 연결에 {@code unlinked_at} 을 채운다.
     *
     * <p>비-null 확인에 그치지 않고 <b>주입된 시계의 현재와 같은 순간</b>인지 본다 — 시스템 시계를
     * 직접 부르는 구현이 이 단언에 걸린다. 값이 언제인지가 중요한 이유는 이 시각이 "언제부터 보호자가
     * 아니었나" 를 답하는 유일한 기록이기 때문이다(과거 이력 보존, UF-P-01).
     */
    @Test
    void 퇴원_처리하면_보호자_연결의_unlinked_at_이_채워진다() throws Exception {
        퇴원시킨다(SIBLING_1_ID);

        assertThat(해제_시각(SIBLING_1_ID))
                .as("고정한 시계의 현재와 같은 순간이어야 한다 — 시스템 시계를 부르면 이 단언이 문다")
                .isEqualTo(OffsetDateTime.now(clock));
    }

    /**
     * 해제는 <b>그 학생의 연결만</b> 건드린다 — 같은 보호자의 다른 자녀는 그대로다.
     *
     * <p>이것이 없으면 "보호자의 연결 전부를 해제" 하는 구현이 위 단언을 지나간다. 그 상태의 증상은
     * 형제 중 하나가 퇴원하자 <b>나머지 자녀까지 앱에서 사라지는</b> 것이다.
     */
    @Test
    void 퇴원은_같은_보호자의_다른_자녀_연결을_건드리지_않는다() throws Exception {
        퇴원시킨다(SIBLING_1_ID);

        assertThat(해제_시각(SIBLING_2_ID))
                .as("퇴원하지 않은 자녀의 연결은 살아 있어야 한다")
                .isNull();
    }

    /** 퇴원한 자녀는 학부모 앱의 자녀 목록에서 빠지고, 남은 자녀는 그대로 보인다(§3.1). */
    @Test
    void 퇴원한_자녀는_보호자의_자녀_목록에서_빠진다() throws Exception {
        퇴원시킨다(SIBLING_1_ID);

        mockMvc.perform(get("/api/v1/me/students")
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_1_ID)).isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_2_ID))
                        .isNotEmpty());
    }

    /**
     * 행 자체는 남는다 — 해제는 삭제가 아니다({@code ERD §7.1} "과거 이력 보존").
     *
     * <p>지워 버리면 목록에서 빠지는 것은 같아 위 단언들이 전부 통과하지만, "이 학생을 누가 데려갔었나"
     * 를 되짚을 수단이 사라진다.
     */
    @Test
    void 퇴원해도_guardian_student_행_자체는_남는다() throws Exception {
        퇴원시킨다(SIBLING_1_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ?",
                Integer.class, 보호자_식별자(), SIBLING_1_ID))
                .as("연결 해제는 행 삭제가 아니다 — 과거 이력이 보존돼야 한다")
                .isEqualTo(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private void 퇴원시킨다(long studentId) throws Exception {
        mockMvc.perform(delete("/api/v1/staff/students/" + studentId)
                        .header("Authorization", 토큰(2L, Role.STAFF)))
                .andExpect(status().isOk());
        entityManager.flush();
    }

    private OffsetDateTime 해제_시각(long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT unlinked_at FROM guardian_student WHERE guardian_id = ? AND student_id = ?",
                OffsetDateTime.class, 보호자_식별자(), studentId);
    }

    private long 보호자_식별자() {
        return jdbcTemplate.queryForObject("SELECT id FROM guardian WHERE account_id = ?", Long.class,
                GUARDIAN_SIBLINGS_ACCOUNT);
    }

    private String 토큰(long accountId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, role, AccountStatus.ACTIVE);
    }
}
