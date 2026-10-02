package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 관계자의 학생 요일별 주소 조회(API_SPEC §5.11 {@code GET /staff/students/{id}/weekly-address}, STU-06 · Ruling 498).
 *
 * <p>주소 원문·좌표는 L3 라 조회마다 감사 {@code read} 가 남는다(Ruling 333) — 같은 행위자가 같은 학생을 10분 안에 다시
 * 조회하면 새 행을 쓰지 않는다(Ruling 445). {@code @Transactional} 이 부재하다 — 감사 행이 {@code REQUIRES_NEW} 로 커밋되므로
 * 시험 끝에 이 시험이 만든 행을 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffWeeklyAddressControllerTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final String ACADEMY_B_STUDENT = "6";

    /** 시험마다 다른 행위자를 쓴다 — 묶기 키가 행위자·학생이라 같은 행위자가 같은 학생을 이어서 보면 두 번째 시험에 행이 안 생긴다. */
    private static final long ACTOR_1 = 7_460_000_001L;

    private static final long ACTOR_2 = 7_460_000_002L;

    private static final long ACTOR_3 = 7_460_000_003L;

    private static final long ACTOR_4 = 7_460_000_004L;

    private static final long ACTOR_5 = 7_460_000_005L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from audit_log where actor_account_id between ? and ?", ACTOR_1, ACTOR_5);
    }

    @Test
    void 같은_학원_학생의_요일별_주소를_요일_방향_순으로_돌려준다() throws Exception {
        mockMvc.perform(get(url(SeedFixtures.STUDENT_SIBLING_1_ID)).header("Authorization", 관계자_토큰(ACTOR_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].weekday").value("mon"))
                .andExpect(jsonPath("$.data.entries[0].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.entries[0].address").isNotEmpty());
    }

    @Test
    void 조회하면_L3_조회_감사_행이_학생_기준으로_1건_남는다() throws Exception {
        mockMvc.perform(get(url(SeedFixtures.STUDENT_SIBLING_2_ID)).header("Authorization", 관계자_토큰(ACTOR_2)))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where actor_account_id = ? and action = 'read' "
                        + "and target_type = 'student' and target_id = ?",
                Integer.class, ACTOR_2, Long.valueOf(SeedFixtures.STUDENT_SIBLING_2_ID))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select detail -> 'fields' from audit_log where actor_account_id = ? and action = 'read'",
                String.class, ACTOR_2)).as("어떤 필드를 읽었는지 — 주소 원문").isEqualTo("[\"weekly_address\"]");
    }

    @Test
    void 같은_학생을_10분_안에_다시_조회해도_감사_행이_늘지_않는다() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(url(SeedFixtures.STUDENT_UNLINKED_ID)).header("Authorization", 관계자_토큰(ACTOR_3)))
                    .andExpect(status().isOk());
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where actor_account_id = ? and action = 'read'", Integer.class,
                ACTOR_3)).isEqualTo(1);
    }

    @Test
    void 다른_학원_학생은_404_이고_감사_행도_남기지_않는다() throws Exception {
        mockMvc.perform(get(url(ACADEMY_B_STUDENT)).header("Authorization", 관계자_토큰(ACTOR_4)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where actor_account_id = ?", Integer.class, ACTOR_4)).isZero();
    }

    /** 퇴원생은 명단에서 빠진 학생이라 존재를 알리지 않는다 — 404 이고 주소 원문·좌표도 감사 행도 나가지 않는다(API_SPEC §5.11 STU-06). */
    @Test
    void 퇴원한_학생은_404_이고_감사_행도_남기지_않는다() throws Exception {
        long withdrawnId = jdbcTemplate.queryForObject(
                "insert into student (academy_id, name, deleted_at) values (?, '퇴원시험학생', now()) returning id",
                Long.class, ACADEMY_A);
        jdbcTemplate.update("insert into weekly_address (student_id, weekday, direction, address, lat, lng, verified, "
                + "updated_at) values (?, 'mon', 'to_academy', '서울시 퇴원 1', 37.5, 127.0, true, now())", withdrawnId);
        try {
            mockMvc.perform(get(url(String.valueOf(withdrawnId))).header("Authorization", 관계자_토큰(ACTOR_5)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from audit_log where actor_account_id = ?", Integer.class, ACTOR_5)).isZero();
        } finally {
            jdbcTemplate.update("delete from weekly_address where student_id = ?", withdrawnId);
            jdbcTemplate.update("delete from student where id = ?", withdrawnId);
        }
    }

    @Test
    void 학부모와_기사는_이_경로로_주소를_읽을_수_없다() throws Exception {
        for (Role role : new Role[] {Role.PARENT, Role.DRIVER}) {
            String token = "Bearer " + tokenProvider.createAccessToken(5L, ACADEMY_A, role, AccountStatus.ACTIVE);
            mockMvc.perform(get(url(SeedFixtures.STUDENT_SIBLING_1_ID)).header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    private static String url(String studentId) {
        return "/api/v1/staff/students/" + studentId + "/weekly-address";
    }

    private String 관계자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }
}
