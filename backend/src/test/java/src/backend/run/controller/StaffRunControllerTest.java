package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * §5.10 {@code /staff/runs} — SCH-02 결과 조회 · SCH-03 임시 추가·취소.
 *
 * <p><b>이 클래스의 최우선 단언은 목록이 날짜로 좁혀지는가</b>다. 목록 조회는 조건이 빠져도 그럴듯하게
 * 동작해(ARCHITECTURE §6.1) 기능 테스트를 통과하는데, 그 상태에서는 오늘 화면에 지난달 회차가 함께
 * 뜬다.
 *
 * <p>임시 회차의 판정 대상은 <b>{@code schedule_id} 가 비어 있는가</b>다 — 그것이 정규 회차와 임시
 * 회차를 가르는 유일한 표시라, 채워 넣는 구현에서는 스케줄을 지울 때 임시 회차까지 영향을 받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRunControllerTest {

    /** 시드의 학원 A 관계자({@code staffA})와 그 학원. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 격리 검증에서 남의 학원 회차를 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    /** 시드 학원 A 의 1호차 · 학원 B 의 1호차. */
    private static final long BUS_A_ID = 1L;

    private static final long BUS_B_ID = 3L;

    /** 시드 회차가 전부 {@code CURRENT_DATE} 라 그것과 겹치지 않는 날짜를 쓴다. */
    private static final String SERVICE_DATE = "2031-05-14";

    private static final String OTHER_DATE = "2031-05-15";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 변경 감지가 만든 UPDATE 는 커밋 시점에야 나간다 — 롤백되는 테스트에서 {@link JdbcTemplate} 로
     * 행을 읽어 보려면 그 전에 {@code flush()} 로 밀어내야 한다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    /** 시각을 고정한다(횡단 규칙 1) — "오늘" 판정이 주입된 시계를 보는지 재려면 고정이 필요하다. */
    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2026-08-26T02:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    // ── SCH-03 임시 추가 ──────────────────────────────────────────────────

    /**
     * 임시 추가한 회차는 {@code schedule_id} 가 비어 있다(§5.10).
     *
     * <p>확정 시각이 함께 채워지는 것도 본다 — {@code confirm_at} 은 NOT NULL 이라 빠지면 저장 자체가
     * 안 되지만, <b>값이 출발 30분 전인지</b>는 별개다. 값을 보지 않으면 아무 시각이나 넣는 구현이
     * 통과한다({@code ck_run_confirm_at} 이 그 아래에서 한 번 더 막지만, 그 제약을 지웠을 때 이
     * 단언이 남아 있어야 정책이 코드에도 고정된 것이 된다).
     */
    @Test
    void 임시_추가한_회차는_schedule_id_가_비어_있다() throws Exception {
        long runId = 임시_추가된_회차_id(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "07:45");

        assertThat(jdbcTemplate.queryForObject("SELECT schedule_id FROM run WHERE id = ?", Long.class, runId))
                .as("임시 회차임을 나타내는 유일한 표시가 schedule_id 부재다")
                .isNull();
        OffsetDateTime 출발 = 회차_시각(runId, "depart_time");
        assertThat(출발).isEqualTo(LocalDate.parse(SERVICE_DATE).atTime(7, 45)
                .atZone(clock.getZone()).toOffsetDateTime());
        assertThat(회차_시각(runId, "confirm_at")).isEqualTo(출발.minusMinutes(30));
    }

    /** 다른 학원의 차량으로는 회차를 추가할 수 없다 — 그 차량의 존재 여부를 드러내지 않는다. */
    @Test
    void 다른_학원의_차량으로는_회차를_추가할_수_없다() throws Exception {
        임시_추가한다(관계자A_토큰(), BUS_B_ID, SERVICE_DATE, "to_academy", "07:50")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
    }

    /**
     * 같은 차량·날짜·방향·시각을 두 번 추가하면 {@code 409 DUPLICATE_RUN} 이다(§5.10).
     *
     * <p>네 값 중 <b>하나만 다른</b> 요청이 통과하는 것까지 함께 본다 — 그것이 없으면 조합이 아니라
     * 차량 하나로 막는 구현(그 차량의 다른 날 회차까지 거부)이 이 단언을 통과한다.
     */
    @Test
    void 같은_조합의_회차를_두_번_추가하면_거부된다() throws Exception {
        임시_추가한다(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "07:55")
                .andExpect(status().isOk());

        임시_추가한다(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "07:55")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RUN"));

        임시_추가한다(관계자A_토큰(), BUS_A_ID, OTHER_DATE, "to_academy", "07:55")
                .andExpect(status().isOk());
    }

    // ── SCH-03 임시 취소 ──────────────────────────────────────────────────

    /**
     * 임시 취소한 회차는 {@code canceled_at} 이 채워지고 <b>행은 남는다</b>(§5.10).
     *
     * <p>행 잔존을 함께 보지 않으면 삭제로 구현한 것이 통과한다 — 그 구현에서는 다음 배치가 지워진
     * 회차를 그대로 다시 만들어 취소가 사라진다.
     */
    @Test
    void 임시_취소한_회차는_canceled_at_이_채워진다() throws Exception {
        long runId = 임시_추가된_회차_id(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "08:05");

        취소한다(관계자A_토큰(), runId).andExpect(status().isOk());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE id = ?", Integer.class, runId))
                .as("취소는 표시이지 삭제가 아니다 — 지우면 다음 배치가 그대로 다시 만든다").isEqualTo(1);
        assertThat(회차_시각(runId, "canceled_at")).isNotNull();
    }

    /** 남의 학원 회차를 {@code {id}} 로 지목한 취소는 {@code 404 RUN_NOT_FOUND} 다(Ruling 163). */
    @Test
    void 다른_학원의_회차를_취소하면_404_이다() throws Exception {
        long runId = 임시_추가된_회차_id(관계자B_토큰(), BUS_B_ID, SERVICE_DATE, "to_academy", "08:15");
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM run WHERE id = ?", Long.class, runId))
                .as("대상이 실재해야 격리가 무언가를 격리한 것이 된다").isEqualTo(ACADEMY_B_ID);

        취소한다(관계자A_토큰(), runId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    // ── SCH-02 결과 조회 ──────────────────────────────────────────────────

    /**
     * 목록은 <b>그 날짜의</b> 회차만 돌려준다(§5.10).
     *
     * <p>다른 날짜의 회차를 함께 만들어 두고 그것이 <b>빠지는 것</b>까지 본다 — 만든 것이 나오는지만
     * 보면 날짜 조건을 통째로 지운 구현이 통과한다.
     */
    @Test
    void 오늘_회차_조회는_그_날짜의_회차만_돌려준다() throws Exception {
        long 그날 = 임시_추가된_회차_id(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "09:05");
        long 다른날 = 임시_추가된_회차_id(관계자A_토큰(), BUS_A_ID, OTHER_DATE, "to_academy", "09:05");

        List<Integer> ids = JsonPath.read(목록_본문(관계자A_토큰(), SERVICE_DATE), "$.data[*].id");

        assertThat(ids).contains((int) 그날);
        assertThat(ids).as("날짜 조건이 빠지면 오늘 화면에 다른 날 회차가 함께 뜬다").doesNotContain((int) 다른날);
    }

    /** 목록은 소속 학원 것만 담는다 — 학원 조건이 빠져도 목록은 그럴듯하게 동작한다. */
    @Test
    void 회차_목록은_소속_학원_것만_돌려준다() throws Exception {
        long 우리 = 임시_추가된_회차_id(관계자A_토큰(), BUS_A_ID, SERVICE_DATE, "to_academy", "09:15");
        long 남의것 = 임시_추가된_회차_id(관계자B_토큰(), BUS_B_ID, SERVICE_DATE, "to_academy", "09:15");

        List<Integer> ids = JsonPath.read(목록_본문(관계자A_토큰(), SERVICE_DATE), "$.data[*].id");

        assertThat(ids).contains((int) 우리);
        assertThat(ids).as("타 학원 회차가 섞이면 격리가 샌 것이다").doesNotContain((int) 남의것);
    }

    /**
     * 날짜를 주지 않으면 <b>오늘</b>이다 — 기준은 주입된 {@code Clock} 이다.
     *
     * <p>시드가 오늘 날짜의 회차를 들고 있어 그것이 실리는 것으로 판정한다. 시드가 비면 이 단언이
     * 아무것도 검사하지 않으므로 그 사실을 먼저 확인한다.
     */
    @Test
    void 날짜를_주지_않으면_오늘_회차를_돌려준다() throws Exception {
        LocalDate 오늘 = LocalDate.now(clock);
        Integer 오늘_회차_수 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run WHERE academy_id = ? AND service_date = ?", Integer.class,
                ACADEMY_A_ID, 오늘);
        assertThat(오늘_회차_수).as("오늘 회차가 없으면 아래 단언은 아무것도 검사하지 않는다").isPositive();

        List<Integer> ids = JsonPath.read(목록_본문(관계자A_토큰(), null), "$.data[*].id");

        assertThat(ids).hasSize(오늘_회차_수);
    }

    /** 회차 목록은 그 회차의 배치를 함께 싣는다 — 배치 결과를 되읽는 유일한 경로다(§5.14). */
    @Test
    void 회차_목록은_그_회차의_배치를_함께_싣는다() throws Exception {
        LocalDate 오늘 = LocalDate.now(clock);
        Long 배치가_있는_회차 = jdbcTemplate.queryForObject(
                "SELECT r.id FROM run r WHERE r.academy_id = ? AND r.service_date = ? AND EXISTS "
                        + "(SELECT 1 FROM assignment a WHERE a.run_id = r.id) ORDER BY r.id LIMIT 1",
                Long.class, ACADEMY_A_ID, 오늘);

        String body = 목록_본문(관계자A_토큰(), null);

        assertThat(JsonPath.<List<String>>read(body,
                "$.data[?(@.id == %d)].assignments[*].role".formatted(배치가_있는_회차)))
                .as("배치를 빼면 §5.14 로 붙인 담당자를 되읽을 경로가 부재해진다")
                .isNotEmpty();
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private ResultActions 임시_추가한다(String token, long busId, String serviceDate, String direction,
            String departTime) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/runs")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bus_id":%d,"service_date":"%s","direction":"%s","depart_time":"%s",
                         "origin_name":"임시 집결지","destination_name":"바래다학원"}"""
                        .formatted(busId, serviceDate, direction, departTime)));
    }

    private long 임시_추가된_회차_id(String token, long busId, String serviceDate, String direction,
            String departTime) throws Exception {
        MvcResult result = 임시_추가한다(token, busId, serviceDate, direction, departTime)
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private ResultActions 취소한다(String token, long runId) throws Exception {
        return mockMvc.perform(delete("/api/v1/staff/runs/" + runId).header("Authorization", token));
    }

    private String 목록_본문(String token, String serviceDate) throws Exception {
        String uri = serviceDate == null ? "/api/v1/staff/runs" : "/api/v1/staff/runs?service_date=" + serviceDate;
        return 본문(mockMvc.perform(get(uri).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
    }

    private OffsetDateTime 회차_시각(long runId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM run WHERE id = ?", OffsetDateTime.class,
                runId);
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
