package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.student.geocoding.impl.StubGeocodingClient;

/**
 * §3.7 요일별 등하원 주소 — P-05 · STU-05 · STU-06.
 *
 * <p><b>주소 문자열이 곧 좌표다</b>({@link StubGeocodingClient}) — 번지가 이웃하면 약 11m, 열 남짓
 * 벌어지면 백 m 단위로 떨어진다. 근접 병합 임계(50m) 양쪽을 <b>입력만 바꿔</b> 밟을 수 있는 것이
 * 결정론적 스텁을 쓰는 이유이고, 난수를 섞으면 아래 단언들이 실행마다 갈린다.
 *
 * <p>승하차지 매칭 결과는 응답이 아니라 <b>DB 를 되읽어</b> 확인한다 — 검사 대상인 API 로 대조군을
 * 만들면 그 API 가 잘못돼도 대조군이 함께 틀려 아무것도 못 잡는다.
 *
 * <p>학원 B 학생(S6)을 <b>새로 만드는 쪽</b>으로 쓴다. 시드가 학원 A 학생 5명에게는 요일 × 방향
 * 70행을 이미 깔아 두었고 B 학생에게는 깔지 않아서다 — 덮어쓰기는 A 쪽에서, 신규 생성은 B 쪽에서
 * 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WeeklyAddressControllerTest {

    private static final String WEEKLY_ADDRESS = "/api/v1/students/%d/weekly-address";

    /** 형제 S1·S2 의 보호자({@code parentA1}) — 두 자녀가 같은 학원이라 승하차지 공유를 볼 수 있다. */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    /** S5 의 보호자({@code parentA3}) — S1 과는 연결이 부재해 {@code 403} 대조군이다. */
    private static final long UNLINKED_GUARDIAN_ACCOUNT = 7L;

    /** 학원 B 보호자({@code parentB1}) — S6 의 보호자이고 학원 격리 대조군이다. */
    private static final long ACADEMY_B_GUARDIAN_ACCOUNT = 9L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);
    private static final long SIBLING_2_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_2_ID);

    /** 학원 B 학생({@code studentB1}) — 시드에 요일별 주소가 부재하다. */
    private static final long ACADEMY_B_STUDENT_ID = 6L;

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    /** 번지가 이웃해 약 11m 떨어진 두 주소 — 임계(50m) 안이다. */
    private static final String ADDRESS = "서울시 테스트로 100";
    private static final String NEAR_ADDRESS = "서울시 테스트로 101";

    /** 번지가 30 벌어져 약 330m 떨어진 주소 — 임계 밖이다. */
    private static final String FAR_ADDRESS = "서울시 테스트로 130";

    /**
     * 번지 4 차이 — {@code 0.0004}도 × 111,320m 로 약 <b>44.5m</b>, 임계(50m) 바로 안쪽이다.
     *
     * <p>{@link #NEAR_ADDRESS}(약 11m)·{@link #FAR_ADDRESS}(약 334m)만으로는 임계가 <b>존재한다</b>는
     * 것만 고정되고 그 <b>값</b>은 고정되지 않는다 — 실제로 50을 300으로 여섯 배 늘려도 15건이 전부
     * 초록이었다(게이트 리뷰 실측). 경계 양쪽을 함께 밟아야 값이 잠긴다.
     */
    private static final String JUST_INSIDE_ADDRESS = "서울시 테스트로 104";

    /** 번지 5 차이 — 약 <b>55.7m</b> 로 임계 바로 바깥이다. 후보 상자 안이면서 원 밖인 지점이기도 하다. */
    private static final String JUST_OUTSIDE_ADDRESS = "서울시 테스트로 105";

    /** 끝에 번지가 없어 공급자가 결과 0건으로 답하는 주소. */
    private static final String UNVERIFIABLE_ADDRESS = "번지가 없는 어딘가";

    private static final String UNAVAILABLE_ADDRESS = StubGeocodingClient.UNAVAILABLE_MARKER + "로 100";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 변경 감지가 만든 UPDATE 는 커밋 시점에야 나간다 — {@link JdbcTemplate} 로 읽기 전에 밀어낸다. */
    @PersistenceContext
    private EntityManager entityManager;

    // ── 저장 형태 (목표 6 ①②) ───────────────────────────────────────────

    /**
     * 요일 × 방향 조합마다 <b>독립 행</b>이다(C-16) — 기본 주소 개념이 부재하기 때문이다(C-12).
     *
     * <p>행 수만 세면 "한 행에 요일을 배열로 담은 구현" 과 구별되지 않으므로, 두 행이 서로 다른
     * {@code id} 를 갖고 각각 자기 방향을 들고 있는지까지 본다.
     */
    @Test
    void 같은_요일의_등원과_하원이_각각_독립_행으로_저장된다() throws Exception {
        주소를_저장한다(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B, ACADEMY_B_STUDENT_ID,
                항목("mon", "to_academy", ADDRESS), 항목("mon", "from_academy", FAR_ADDRESS));

        assertThat(jdbcTemplate.queryForList(
                "SELECT direction, address FROM weekly_address WHERE student_id = ? AND weekday = 'mon' ORDER BY direction",
                ACADEMY_B_STUDENT_ID))
                .extracting(row -> row.get("direction") + "/" + row.get("address"))
                .containsExactly("from_academy/" + FAR_ADDRESS, "to_academy/" + ADDRESS);
    }

    /**
     * 같은 칸을 다시 보내면 행이 늘지 않고 <b>덮어쓴다</b>(§3.7 즉시 반영, Ruling 151).
     *
     * <p>행 수와 값을 함께 본다 — 수만 보면 옛 행을 지우고 새로 넣는 구현이 통과하고({@code id} 가
     * 바뀌어 이 주소를 참조하던 것들이 끊긴다), 값만 보면 행이 쌓이는 구현이 통과한다(어느 것이 현재
     * 값인지 판정할 수단이 사라진다).
     */
    @Test
    void 같은_요일과_방향을_두_번_저장하면_행이_늘지_않고_덮어쓴다() throws Exception {
        주소를_저장한다(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B, ACADEMY_B_STUDENT_ID,
                항목("tue", "to_academy", ADDRESS));
        Long 첫_행 = 행_식별자(ACADEMY_B_STUDENT_ID, "tue", "to_academy");

        주소를_저장한다(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B, ACADEMY_B_STUDENT_ID,
                항목("tue", "to_academy", FAR_ADDRESS));

        assertThat(행_수(ACADEMY_B_STUDENT_ID)).isEqualTo(1);
        assertThat(행_식별자(ACADEMY_B_STUDENT_ID, "tue", "to_academy")).isEqualTo(첫_행);
        assertThat(주소(ACADEMY_B_STUDENT_ID, "tue", "to_academy")).isEqualTo(FAR_ADDRESS);
    }

    /**
     * 한 요청이 같은 칸을 두 번 담으면 <b>DB UNIQUE</b> 가 막고 {@code 409} 로 번역된다(목표 6 ①).
     *
     * <p>애플리케이션 선검사가 아니라 제약이 막는 것이 요점이다 — 선검사는 동시에 도착한 두 요청
     * 사이에서 아무것도 막지 못하고, 그때 한 칸에 두 행이 생겨 노선 계산이 어느 것을 쓸지 정할 수단을
     * 잃는다. {@code 500} 이 아닌 것도 함께 본다(제약 위반이 그대로 샌 형태).
     *
     * <p>행 수를 뒤에서 세지 않는다 — PostgreSQL 은 제약 위반이 나면 그 트랜잭션을 abort 상태로
     * 두어 이어지는 조회가 전부 실패한다. 저장되지 않았다는 것은 그 abort 자체가 보증한다.
     */
    @Test
    void 한_요청에_같은_요일과_방향을_두_번_담으면_409_다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("wed", "to_academy", ADDRESS), 항목("wed", "to_academy", FAR_ADDRESS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_WEEKLY_ADDRESS"));
    }

    // ── 검증 실패 (목표 6 ③) ─────────────────────────────────────────────

    /** 검증에 실패한 주소는 {@code 422} 이고 행이 <b>남지 않는다</b>(§3.7 저장 보류). */
    @Test
    void 검증에_실패한_주소는_422_이고_weekly_address_행이_남지_않는다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("thu", "to_academy", UNVERIFIABLE_ADDRESS))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"));

        entityManager.flush();
        assertThat(행_수(ACADEMY_B_STUDENT_ID)).isZero();
    }

    /**
     * 한 칸이라도 실패하면 <b>같은 요청의 성공분도</b> 저장되지 않는다.
     *
     * <p>부분 저장을 하면 {@code 422} 를 받은 사용자가 무엇이 저장됐는지 알 수단이 부재하다. 이 단언이
     * 없으면 "성공한 것만 먼저 넣고 실패를 던지는" 구현이 위 단언을 통과한다 — 실패 칸만 검사하기
     * 때문이다.
     */
    @Test
    void 일부만_실패해도_같은_요청의_성공분이_저장되지_않는다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("fri", "to_academy", ADDRESS),
                                항목("fri", "from_academy", UNVERIFIABLE_ADDRESS))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.failed_entries[0]").value(UNVERIFIABLE_ADDRESS));

        entityManager.flush();
        assertThat(행_수(ACADEMY_B_STUDENT_ID)).isZero();
    }

    /**
     * 공급자에 <b>닿지 못한</b> 것은 {@code 422} 가 아니라 {@code 503} 이다(Ruling 157).
     *
     * <p>같은 코드로 뭉개면 네이버가 5분 멈춘 동안 학부모 전원이 "주소가 틀렸다" 는 안내를 받고,
     * 고칠 것이 없는 주소를 고치려 든다. 두 경우에 사용자가 할 일이 정반대라 코드를 가른다.
     */
    @Test
    void 공급자에_닿지_못하면_422_가_아니라_503_이다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("sat", "to_academy", UNAVAILABLE_ADDRESS))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_UNAVAILABLE"));

        entityManager.flush();
        assertThat(행_수(ACADEMY_B_STUDENT_ID)).isZero();
    }

    // ── 승하차지 매칭 (STU-05) ───────────────────────────────────────────

    /**
     * 검증에 성공하면 {@code stop_id} 가 채워지고 {@code stop} 행이 실재한다(목표 1 · STU-05).
     *
     * <p>이 연결이 Phase 6 노선 계산의 입력이다(ARCHITECTURE §8.1) — 단언이 없으면 주소만 저장하고
     * 매칭을 건너뛴 구현이 통과하는데, 그 상태로는 계산할 대상이 비어 있다.
     */
    @Test
    void 검증에_성공하면_stop_id_가_채워지고_stop_행이_생긴다() throws Exception {
        MvcResult result = 주소를_저장한다(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B, ACADEMY_B_STUDENT_ID,
                항목("sun", "to_academy", ADDRESS));

        Long stopId = 승하차지(ACADEMY_B_STUDENT_ID, "sun", "to_academy");
        assertThat(stopId).isNotNull();
        assertThat(JsonPath.<Number>read(본문(result), "$.data.entries[0].stop_id").longValue()).isEqualTo(stopId);
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM stop WHERE id = ?", Long.class, stopId))
                .isEqualTo(ACADEMY_B);
    }

    /** 같은 주소를 쓰는 두 학생은 한 승하차지로 묶인다 — 매칭 결과이지 정류장 편성이 아니다(ERD {@code stop}). */
    @Test
    void 같은_주소를_쓰는_두_학생이_같은_stop_에_묶인다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("mon", "to_academy", ADDRESS));
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_2_ID, 항목("mon", "to_academy", ADDRESS));

        assertThat(승하차지(SIBLING_2_ID, "mon", "to_academy"))
                .isEqualTo(승하차지(SIBLING_1_ID, "mon", "to_academy"));
    }

    /** 임계 거리 안의 서로 <b>다른</b> 주소도 한 승하차지로 묶인다(근접 병합). */
    @Test
    void 근접_좌표의_두_주소가_같은_stop_에_묶인다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("tue", "to_academy", ADDRESS));
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_2_ID, 항목("tue", "to_academy", NEAR_ADDRESS));

        assertThat(승하차지(SIBLING_2_ID, "tue", "to_academy"))
                .isEqualTo(승하차지(SIBLING_1_ID, "tue", "to_academy"));
    }

    /**
     * 임계를 넘는 두 주소는 서로 다른 승하차지다 — 경계 반대편을 함께 봐야 "전부 묶는 구현" 이
     * 걸린다.
     */
    @Test
    void 임계_거리를_넘는_두_주소는_서로_다른_stop_이_된다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("wed", "to_academy", ADDRESS));
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_2_ID, 항목("wed", "to_academy", FAR_ADDRESS));

        assertThat(승하차지(SIBLING_2_ID, "wed", "to_academy"))
                .isNotEqualTo(승하차지(SIBLING_1_ID, "wed", "to_academy"));
    }

    /**
     * 임계 <b>바로 안쪽</b>(약 44.5m)의 두 주소는 한 승하차지로 묶인다.
     *
     * <p>아래 바깥쪽 단언과 짝이다 — 둘이 함께 임계를 {@code (44.5, 55.7]} 구간에 가둔다. 한쪽만
     * 있으면 임계를 0으로 만들거나 무한대로 늘리는 변경 중 한 방향만 걸린다.
     */
    @Test
    void 임계_바로_안쪽인_44m_떨어진_두_주소는_같은_stop_에_묶인다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("sat", "to_academy", ADDRESS));
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_2_ID,
                항목("sat", "to_academy", JUST_INSIDE_ADDRESS));

        assertThat(승하차지(SIBLING_2_ID, "sat", "to_academy"))
                .as("임계 안인데 갈렸다 — MERGE_RADIUS_METERS 가 44.5m 아래로 줄었다")
                .isEqualTo(승하차지(SIBLING_1_ID, "sat", "to_academy"));
    }

    /** 임계 <b>바로 바깥</b>(약 55.7m)의 두 주소는 갈린다 — 값을 위에서 눌러 고정하는 쪽이다. */
    @Test
    void 임계_바로_바깥인_56m_떨어진_두_주소는_다른_stop_이_된다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("sun", "to_academy", ADDRESS));
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_2_ID,
                항목("sun", "to_academy", JUST_OUTSIDE_ADDRESS));

        assertThat(승하차지(SIBLING_2_ID, "sun", "to_academy"))
                .as("임계 밖인데 묶였다 — MERGE_RADIUS_METERS 가 55.7m 위로 늘었다")
                .isNotEqualTo(승하차지(SIBLING_1_ID, "sun", "to_academy"));
    }

    /**
     * 매칭 범위는 <b>학원 안</b>이다(ERD {@code stop.academy_id}) — 좌표가 같아도 다른 학원의
     * 승하차지에는 붙지 않는다.
     *
     * <p>범위가 새면 A 학원 버스의 노선에 B 학원 학생의 승하차지가 실린다. 좌표가 정확히 같은
     * 입력으로 보는 이유는, 거리 조건만 남고 학원 조건이 빠진 구현을 그때만 가려낼 수 있어서다.
     */
    @Test
    void 다른_학원의_승하차지에는_매칭되지_않는다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("thu", "to_academy", ADDRESS));
        주소를_저장한다(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B, ACADEMY_B_STUDENT_ID,
                항목("thu", "to_academy", ADDRESS));

        Long 학원A_승하차지 = 승하차지(SIBLING_1_ID, "thu", "to_academy");
        Long 학원B_승하차지 = 승하차지(ACADEMY_B_STUDENT_ID, "thu", "to_academy");
        assertThat(학원B_승하차지).isNotEqualTo(학원A_승하차지);
        assertThat(jdbcTemplate.queryForObject("SELECT academy_id FROM stop WHERE id = ?", Long.class, 학원B_승하차지))
                .isEqualTo(ACADEMY_B);
    }

    // ── 조회 · 접근 범위 ─────────────────────────────────────────────────

    /** 주소를 수정하면 즉시 반영된다(Ruling 151) — 조회가 방금 보낸 값을 그대로 돌려준다. */
    @Test
    void 주소를_수정하면_조회에_즉시_반영된다() throws Exception {
        주소를_저장한다(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, SIBLING_1_ID, 항목("fri", "to_academy", FAR_ADDRESS));
        entityManager.flush();

        mockMvc.perform(get(WEEKLY_ADDRESS.formatted(SIBLING_1_ID))
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[?(@.weekday == 'fri' && @.direction == 'to_academy')].address")
                        .value(Matchers.contains(FAR_ADDRESS)));
    }

    /** 요일별 주소 조회는 시드가 깔아 둔 14칸(요일 7 × 방향 2)을 전부 돌려준다. */
    @Test
    void 조회는_요일_7종과_방향_2종의_14칸을_돌려준다() throws Exception {
        mockMvc.perform(get(WEEKLY_ADDRESS.formatted(SIBLING_1_ID))
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries.length()").value(14))
                .andExpect(jsonPath("$.data.entries[0].weekday").value("mon"));
    }

    /**
     * 연결 부재 자녀는 조회·저장 모두 {@code 403} 이다(§3.7).
     *
     * <p>판정은 {@code student.access} 의 단일 지점이 하고 이 경로가 그것을 <b>쓴다</b> — 경로마다 손으로
     * 검사하면 다음에 추가되는 학부모 경로가 검사를 빠뜨린 채 태어나고, 그 상태로도 기존 테스트는 전부
     * 초록이다.
     */
    @Test
    void 연결_부재_자녀의_주소는_조회도_저장도_403_이다() throws Exception {
        mockMvc.perform(get(WEEKLY_ADDRESS.formatted(SIBLING_1_ID))
                        .header("Authorization", 토큰(UNLINKED_GUARDIAN_ACCOUNT, ACADEMY_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(SIBLING_1_ID))
                        .header("Authorization", 토큰(UNLINKED_GUARDIAN_ACCOUNT, ACADEMY_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("mon", "to_academy", ADDRESS))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 사양에 없는 요일·방향 값은 조용히 무시되지 않고 {@code 422} 로 거부된다(§9 enum 사전). */
    @Test
    void 사양에_없는_요일은_422_다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("holiday", "to_academy", ADDRESS))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 보조 ─────────────────────────────────────────────────────────────

    private MvcResult 주소를_저장한다(long accountId, Long academyId, long studentId, String... entries)
            throws Exception {
        MvcResult result = mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(studentId))
                        .header("Authorization", 토큰(accountId, academyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(entries)))
                .andExpect(status().isOk())
                .andReturn();
        entityManager.flush();
        return result;
    }

    private static String 항목(String weekday, String direction, String address) {
        return """
                {"weekday":"%s","direction":"%s","address":"%s"}""".formatted(weekday, direction, address);
    }

    private static String 본문(String... entries) {
        return "{\"entries\":[" + String.join(",", entries) + "]}";
    }

    private String 토큰(long accountId, Long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }

    private static String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int 행_수(long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_address WHERE student_id = ?", Integer.class, studentId);
    }

    private Long 행_식별자(long studentId, String weekday, String direction) {
        return 칸(studentId, weekday, direction, "id");
    }

    private Long 승하차지(long studentId, String weekday, String direction) {
        return 칸(studentId, weekday, direction, "stop_id");
    }

    private Long 칸(long studentId, String weekday, String direction, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM weekly_address WHERE student_id = ? AND weekday = ? AND direction = ?",
                Long.class, studentId, weekday, direction);
    }

    private String 주소(long studentId, String weekday, String direction) {
        return jdbcTemplate.queryForObject(
                "SELECT address FROM weekly_address WHERE student_id = ? AND weekday = ? AND direction = ?",
                String.class, studentId, weekday, direction);
    }
}
