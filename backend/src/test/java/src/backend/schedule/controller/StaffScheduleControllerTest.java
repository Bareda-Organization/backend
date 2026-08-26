package src.backend.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.10 {@code /staff/schedules} — SCH-01 · A-09 (Ruling 153 이 계약을 확정한 경로).
 *
 * <p><b>이 클래스의 최우선 단언은 유일성 조합 넷</b>({@code bus_id}·{@code weekday}·
 * {@code direction}·{@code depart_time})<b>이 실제로 막히는가</b>다. 막히지 않으면 같은 차량이 같은
 * 시각에 두 번 출발하는 계획이 서고, 일일 회차 생성이 그 계획을 그대로 회차로 옮긴다.
 *
 * <p>삭제가 <b>행을 지우는 것</b>이라는 점도 함께 본다 — {@code schedule} 에 {@code deleted_at} 이
 * 부재한 것이 ERD 의 설계이고, 이미 만들어진 회차는 FK {@code SET NULL} 로 살아남는다. 회차까지 함께
 * 사라지면 과거 운행 기록이 스케줄 정리 한 번에 지워진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffScheduleControllerTest {

    /** 시드의 학원 A 관계자({@code staffA})와 그 학원. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 격리 검증에서 남의 학원 스케줄을 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /** 시드 학원 A 의 1호차. */
    private static final long BUS_A_ID = 1L;

    /** 시드 학원 B 의 1호차 — 학원 A 토큰으로 지목하면 {@code 404 BUS_NOT_FOUND} 여야 한다. */
    private static final long BUS_B_ID = 3L;

    /** 시드 스케줄이 쓰지 않는 시각 — 시드는 08:00·08:10·08:20·16:00·16:10·16:20 만 쓴다. */
    private static final String FREE_TIME = "09:47";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 변경 감지·삭제가 만든 SQL 은 커밋 시점에야 나간다 — 롤백되는 테스트에서 {@link JdbcTemplate} 로
     * 행을 읽어 보려면 그 전에 {@code flush()} 로 밀어내야 한다. 밀지 않으면 구현이 옳아도 실패한다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // ── SCH-01 등록 ───────────────────────────────────────────────────────

    /**
     * 소속 학원은 <b>토큰이 정한다</b>(§1.5) — 요청 본문에 학원을 담을 자리가 부재한 것이 그 규칙을
     * 지키는 방식이라, 저장된 {@code academy_id} 를 DB 에서 직접 읽어 대조한다.
     */
    @Test
    void 스케줄을_등록하면_학원_소속으로_저장된다() throws Exception {
        long scheduleId = 등록된_스케줄_id(관계자A_토큰(), BUS_A_ID, "mon", "to_academy", FREE_TIME);

        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM schedule WHERE id = ?", Long.class,
                scheduleId))
                .as("응답이 아니라 저장된 행의 학원이 판정 대상이다 — 본문으로 학원을 받으면 여기서 갈린다")
                .isEqualTo(ACADEMY_A_ID);
    }

    /**
     * 유일성 조합 넷이 {@code 409 DUPLICATE_SCHEDULE} 로 막힌다(§5.10 · {@code uk_schedule_bus_weekday_direction_depart}).
     *
     * <p>네 값 중 <b>하나만 다른</b> 요청이 통과하는 것까지 함께 본다 — 그것이 없으면 조합이 아니라
     * 차량 하나로 막는 구현(같은 차량의 등원·하원을 함께 거부)이 이 단언을 통과한다.
     */
    @Test
    void 같은_차량_요일_방향_출발시각_조합을_두_번_등록하면_거부된다() throws Exception {
        등록한다(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", FREE_TIME).andExpect(status().isOk());

        등록한다(관계자A_토큰(), BUS_A_ID, "tue", "to_academy", FREE_TIME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_SCHEDULE"));

        등록한다(관계자A_토큰(), BUS_A_ID, "tue", "from_academy", FREE_TIME)
                .andExpect(status().isOk());
    }

    /** 다른 학원의 차량을 지목한 등록은 {@code 404 BUS_NOT_FOUND} 다 — 그 차량의 존재 여부를 드러내지 않는다. */
    @Test
    void 다른_학원의_차량으로는_스케줄을_등록할_수_없다() throws Exception {
        등록한다(관계자A_토큰(), BUS_B_ID, "wed", "to_academy", FREE_TIME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
    }

    // ── SCH-01 수정 · 삭제 ────────────────────────────────────────────────

    /**
     * {@code active=false} 로 고치면 그 값이 저장된다 — 일일 회차 생성이 읽는 유일한 조건이라
     * 저장되지 않으면 운행하지 않는 회차가 계속 만들어진다.
     */
    @Test
    void 스케줄을_비활성으로_고치면_active_가_false_로_저장된다() throws Exception {
        long scheduleId = 등록된_스케줄_id(관계자A_토큰(), BUS_A_ID, "thu", "to_academy", FREE_TIME);

        수정한다(관계자A_토큰(), scheduleId, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM schedule WHERE id = ?", Boolean.class,
                scheduleId)).isFalse();
    }

    /** 수정도 유일성 조합을 받는다 — 시각만 옮겨도 기존 스케줄과 겹치면 {@code 409} 다. */
    @Test
    void 수정으로_다른_스케줄과_같은_조합이_되면_거부된다() throws Exception {
        등록한다(관계자A_토큰(), BUS_A_ID, "fri", "to_academy", "09:10").andExpect(status().isOk());
        long scheduleId = 등록된_스케줄_id(관계자A_토큰(), BUS_A_ID, "fri", "to_academy", "09:20");

        수정한다(관계자A_토큰(), scheduleId, "{\"depart_time\":\"09:10\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_SCHEDULE"));
    }

    /** 자기 자신을 그대로 다시 보내는 수정은 통과한다 — 걸러내지 않으면 이름만 고치려는 요청이 막힌다. */
    @Test
    void 같은_값을_그대로_다시_보내는_수정은_통과한다() throws Exception {
        long scheduleId = 등록된_스케줄_id(관계자A_토큰(), BUS_A_ID, "sat", "to_academy", FREE_TIME);

        수정한다(관계자A_토큰(), scheduleId,
                "{\"depart_time\":\"%s\",\"origin_name\":\"바뀐 집결지\"}".formatted(FREE_TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.origin_name").value("바뀐 집결지"));
    }

    /**
     * 학원 격리 — 남의 학원 스케줄을 {@code {id}} 로 지목하면 {@code 404 SCHEDULE_NOT_FOUND} 다
     * (Ruling 163: {@code {id}} 지목은 404).
     *
     * <p>대상이 <b>실재</b>하는 것을 함께 확인한다 — 실재하지 않는 id 를 쓰면 격리가 아니라 부재를
     * 검사하는 것이 되어, 학원 조건을 통째로 지워도 이 단언이 통과한다.
     */
    @Test
    void 다른_학원의_스케줄을_수정하면_404_이다() throws Exception {
        long scheduleId = 등록된_스케줄_id(관계자B_토큰(), BUS_B_ID, "sun", "to_academy", FREE_TIME);
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM schedule WHERE id = ?", Long.class,
                scheduleId)).isEqualTo(ACADEMY_B_ID);

        수정한다(관계자A_토큰(), scheduleId, "{\"active\":false}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_NOT_FOUND"));

        삭제한다(관계자A_토큰(), scheduleId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_NOT_FOUND"));
    }

    /**
     * 삭제는 <b>행을 지우고</b>, 그 스케줄로 만들어진 회차는 {@code schedule_id} 가 NULL 이 된 채
     * 남는다(`ERD` FK {@code SET NULL}).
     *
     * <p>회차 잔존을 함께 보지 않으면 CASCADE 로 바꾼 구현이 통과한다 — 그 구현에서는 스케줄 정리
     * 한 번이 과거 운행 기록을 함께 지운다.
     */
    @Test
    void 스케줄을_삭제해도_그_스케줄로_만든_회차는_남는다() throws Exception {
        long scheduleId = 시드_스케줄_id();
        Integer runsBefore = 회차_수(scheduleId);
        assertThat(runsBefore).as("시드 스케줄에 회차가 없으면 이 단언은 아무것도 검사하지 않는다").isPositive();

        삭제한다(관계자A_토큰(), scheduleId).andExpect(status().isOk());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM schedule WHERE id = ?", Integer.class,
                scheduleId)).as("스케줄에는 deleted_at 이 부재하다 — 삭제는 행을 지우는 것이다").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run WHERE schedule_id IS NULL AND id IN "
                        + "(SELECT id FROM run WHERE academy_id = ?)", Integer.class, ACADEMY_A_ID))
                .as("스케줄이 사라져도 과거 회차는 존치한다(FK SET NULL)").isPositive();
    }

    // ── SCH-01 목록 ───────────────────────────────────────────────────────

    /** 목록은 소속 학원 것만 담고 호차를 함께 싣는다 — 목록 조회는 조건이 빠져도 동작해 눈에 띄지 않는다. */
    @Test
    void 스케줄_목록은_소속_학원_것만_돌려준다() throws Exception {
        String bodyOfA = 목록_본문(관계자A_토큰());

        assertThat(bodyOfA).as("목록이 비면 아래 부재 단언은 아무것도 검사하지 않는다").contains("\"bus_no\"");
        assertThat((int) JsonPath.read(bodyOfA, "$.data.items.length()")).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schedule WHERE academy_id <> ?", Integer.class, ACADEMY_A_ID))
                .as("타 학원 스케줄이 실재해야 격리가 무언가를 격리한 것이 된다").isPositive();

        Integer ownedByOther = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schedule WHERE academy_id <> ?", Integer.class, ACADEMY_A_ID);
        assertThat(JsonPath.<java.util.List<Integer>>read(bodyOfA, "$.data.items[*].id"))
                .as("학원 A 목록에 타 학원 스케줄 %d 건 중 하나라도 섞이면 안 된다", ownedByOther)
                .allSatisfy(id -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT academy_id FROM schedule WHERE id = ?", Long.class, id.longValue()))
                        .isEqualTo(ACADEMY_A_ID));
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    /** 시드가 만든 학원 A 의 스케줄 하나 — 회차가 이미 매달려 있어 FK 동작을 볼 수 있다. */
    private long 시드_스케줄_id() {
        return jdbcTemplate.queryForObject(
                "SELECT s.id FROM schedule s WHERE s.academy_id = ? AND EXISTS "
                        + "(SELECT 1 FROM run r WHERE r.schedule_id = s.id) ORDER BY s.id LIMIT 1",
                Long.class, ACADEMY_A_ID);
    }

    private Integer 회차_수(long scheduleId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE schedule_id = ?", Integer.class,
                scheduleId);
    }

    private ResultActions 등록한다(String token, long busId, String weekday, String direction, String departTime)
            throws Exception {
        return mockMvc.perform(post("/api/v1/staff/schedules")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"weekday":"%s","direction":"%s","depart_time":"%s",
                         "origin_name":"중앙 집결지","destination_name":"바래다학원"}"""
                        .formatted(busId, weekday, direction, departTime)));
    }

    private long 등록된_스케줄_id(String token, long busId, String weekday, String direction, String departTime)
            throws Exception {
        MvcResult result = 등록한다(token, busId, weekday, direction, departTime)
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private ResultActions 수정한다(String token, long scheduleId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/schedules/" + scheduleId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 삭제한다(String token, long scheduleId) throws Exception {
        return mockMvc.perform(delete("/api/v1/staff/schedules/" + scheduleId).header("Authorization", token));
    }

    private String 목록_본문(String token) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/schedules?size=100").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 관계자A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    private String 관계자B_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_B_ACCOUNT_ID, ACADEMY_B_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
