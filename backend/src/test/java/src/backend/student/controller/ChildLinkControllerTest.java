package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §3.1~§3.4 자녀 연결 3단계와 자녀 목록 — P-02 · S-05 · ATT-03.
 *
 * <p>연결은 <b>주체가 번갈아 바뀌는</b> 3단계다 — 학부모가 요청하고, 학생이 코드를 만들고, 학부모가
 * 그 코드를 넣는다. 그래서 한 토큰으로 끝까지 갈 수 있는 흐름이 부재하고, 각 단계의 토큰을 바꿔 가며
 * 부른다. 코드 대조는 <b>서버가</b> 한다(§3.4) — 학생 응답의 코드를 학부모 요청에 그대로 넣어 보내는
 * 것이 클라이언트가 하는 전부다.
 *
 * <p>거부 3종(만료 · 불일치 · 재사용)이 <b>같은 {@code 403 LINK_CODE_INVALID}</b> 인 것을 각각
 * 단언한다. 갈라 답하면 "이 코드는 존재하는데 만료됐다" 가 미인증 응답으로 새어 나간다.
 *
 * <p>대조군인 DB 상태는 {@link JdbcTemplate} 으로 직접 읽는다 — 검사 대상인 조회 API 로 대조군을
 * 만들면 그 API 가 잘못돼도 대조군이 함께 틀려 아무것도 못 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChildLinkControllerTest {

    private static final String CHILDREN = "/api/v1/me/students";
    private static final String LINK_REQUESTS = "/api/v1/me/students/link-requests";
    private static final String LINK_CODE = "/api/v1/me/link-code";
    private static final String LINK = "/api/v1/me/students/link";

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    /** 형제 S1·S2 의 보호자({@code parentA1}) — 아직 {@code studentA4} 와는 연결돼 있지 않다. */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    /** S5 의 보호자({@code parentA3}) — 남의 코드를 가로채는 쪽으로 쓴다. */
    private static final long OTHER_GUARDIAN_ACCOUNT = 7L;

    /** {@code studentA4} 를 이미 연결해 둔 보호자({@code parentA2}) — 재연결 409 의 재료다. */
    private static final long ALREADY_LINKED_GUARDIAN_ACCOUNT = 6L;

    /** 계정이 붙은 학원 A 학생({@code studentA4}) — 연결 대상이자 코드 발급 주체다. */
    private static final long STUDENT_A4_ACCOUNT = 10L;

    private static final long STUDENT_A4_ID = 4L;

    /** 학원 B 학생({@code studentB1}) — 학원 격리 대조군. */
    private static final long STUDENT_B1_ACCOUNT = 12L;

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);
    private static final long SIBLING_2_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_2_ID);
    private static final long UNLINKED_STUDENT_ID = Long.parseLong(SeedFixtures.STUDENT_UNLINKED_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 아래 {@link FixedClockConfig} 가 넣어 준 고정 시계 — 만료 경계를 같은 순간으로 대조한다. */
    @Autowired
    private Clock clock;

    /**
     * 변경 감지가 만든 UPDATE 는 커밋 시점에야 나간다 — 롤백되는 테스트에서 {@link JdbcTemplate} 로
     * 행을 읽어 보려면 그 전에 {@code flush()} 로 밀어내야 한다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 시각을 고정한다(횡단 규칙 1) — 만료 판정에 {@code Clock} 을 쓰는 구현과 시스템 시계를 직접
     * 부르는 구현이 이 설정 아래에서 <b>다른 값</b>을 내놓는다.
     *
     * <p>고정하지 않으면 "만료된 코드는 거부된다" 는 단언이 <b>아무 때나 통과</b>한다 — 코드를 과거로
     * 밀어 넣어도 실제 만료 판정이 무엇을 기준으로 하는지 알 수단이 부재하기 때문이다.
     */
    @TestConfiguration
    static class FixedClockConfig {

        /** 값 자체에 의미는 없다 — 고정돼 있다는 사실만이 검사 대상이다. */
        private static final Instant FIXED = Instant.parse("2026-08-26T00:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    // ── ① 연결 요청 (P-02, §3.2) ─────────────────────────────────────────

    /**
     * 요청은 {@code link_request} 를 {@code pending} 으로 남긴다(§3.2 · ERD {@code link_request}).
     *
     * <p>응답의 {@code link_request_id} 만 보면 행을 만들지 않고 번호만 지어낸 구현과 구별되지
     * 않는다 — 그 번호로 DB 를 되읽어 보호자·학생·상태가 실제로 채워졌는지 본다.
     */
    @Test
    void 학부모가_연결을_요청하면_link_request_가_pending_으로_생긴다() throws Exception {
        long requestId = 연결을_요청한다(GUARDIAN_SIBLINGS_ACCOUNT, SeedFixtures.STUDENT_A4_LOGIN_ID);

        entityManager.flush();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT guardian_id, student_id, status FROM link_request WHERE id = ?", requestId))
                .as("요청한 보호자와 지목된 학생이 pending 으로 남아야 한다")
                .containsEntry("guardian_id", 보호자_식별자(GUARDIAN_SIBLINGS_ACCOUNT))
                .containsEntry("student_id", STUDENT_A4_ID)
                .containsEntry("status", "pending");
    }

    /** 요청 만료 시각은 주입된 시계 기준이다(횡단 규칙 1) — 시스템 시계를 직접 부르면 어긋난다. */
    @Test
    void 연결_요청의_만료_시각은_주입된_시계_기준이다() throws Exception {
        MvcResult result = 연결_요청(GUARDIAN_SIBLINGS_ACCOUNT, SeedFixtures.STUDENT_A4_LOGIN_ID)
                .andExpect(status().isCreated())
                .andReturn();

        String expiresAt = JsonPath.read(본문(result), "$.data.expires_at");
        assertThat(OffsetDateTime.parse(expiresAt))
                .as("고정한 시계의 현재보다 뒤여야 하고, 그 차이가 정책 값이다")
                .isAfter(OffsetDateTime.now(clock));
    }

    /** 없는 로그인 아이디는 {@code 404 STUDENT_NOT_FOUND}(§3.2). */
    @Test
    void 없는_학생_로그인아이디로_요청하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        연결_요청(GUARDIAN_SIBLINGS_ACCOUNT, "p5t3없는아이디")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    /**
     * 남의 학원 학생은 <b>없는 것과 같은 응답</b>이어야 한다(§1.5 · Ruling 163).
     *
     * <p>{@code 403} 으로 갈라 답하면 "그 로그인 아이디는 실재한다" 가 응답에서 새어 나가, 학원 밖
     * 사람이 아이디 존재 여부를 훑을 수 있다.
     */
    @Test
    void 타_학원_학생을_지목하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        int before = 요청_수(GUARDIAN_SIBLINGS_ACCOUNT);

        연결_요청(GUARDIAN_SIBLINGS_ACCOUNT, SeedFixtures.STUDENT_B1_LOGIN_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        entityManager.flush();
        assertThat(요청_수(GUARDIAN_SIBLINGS_ACCOUNT))
                .as("거부했다면 요청 행이 늘면 안 된다 — 시드에 이 보호자의 요청이 이미 있어 절대값이 아니라 증감을 본다")
                .isEqualTo(before);
    }

    /** 이미 연결된 자녀를 다시 요청하면 {@code 409 ALREADY_LINKED}(§3.2 · §8.5). */
    @Test
    void 이미_연결된_자녀를_다시_요청하면_409_ALREADY_LINKED_이다() throws Exception {
        연결_요청(ALREADY_LINKED_GUARDIAN_ACCOUNT, SeedFixtures.STUDENT_A4_LOGIN_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_LINKED"));
    }

    /** 보호자 레코드가 없는 계정(기사)은 학부모 경로에 닿을 수 없다(§3.2 권한 · §1.11 {@code FORBIDDEN}). */
    @Test
    void 보호자가_아닌_계정의_연결_요청은_403_FORBIDDEN_이다() throws Exception {
        mockMvc.perform(post(LINK_REQUESTS).header("Authorization", 토큰(13L, ACADEMY_A, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"student_login_id\":\"%s\"}".formatted(SeedFixtures.STUDENT_A4_LOGIN_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── ② 코드 생성 (S-05, §3.3) ─────────────────────────────────────────

    /** 학생이 코드를 만들면 코드와 만료 시각이 함께 나온다(§3.3). */
    @Test
    void 학생이_코드를_생성하면_만료시각과_함께_반환된다() throws Exception {
        연결을_요청한다(GUARDIAN_SIBLINGS_ACCOUNT, SeedFixtures.STUDENT_A4_LOGIN_ID);

        MvcResult result = 코드_생성(STUDENT_A4_ACCOUNT, ACADEMY_A)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").isNotEmpty())
                .andExpect(jsonPath("$.data.expires_at").isNotEmpty())
                .andReturn();

        String expiresAt = JsonPath.read(본문(result), "$.data.expires_at");
        assertThat(OffsetDateTime.parse(expiresAt))
                .as("만료 시각은 주입된 시계 기준 미래여야 한다 — 시스템 시계를 부르면 어긋난다")
                .isAfter(OffsetDateTime.now(clock));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM link_code lc JOIN link_request lr ON lr.id = lc.link_request_id"
                        + " WHERE lr.student_id = ? AND lc.used_at IS NULL", Integer.class, STUDENT_A4_ID))
                .as("발급분을 서버가 보관해야 대조가 성립한다(§3.4 서버 인증)")
                .isEqualTo(1);
    }

    /**
     * 대기 중인 연결 요청이 없으면 만들 코드가 없다 — {@code 404 LINK_REQUEST_NOT_FOUND}.
     *
     * <p>⚠ {@code §3.3} 은 이 경우의 코드를 규정하지 않는다(고유 에러 부재). 그러나
     * {@code link_code.link_request_id} 가 <b>FK NN</b> 이라 요청 없이 코드를 만들 수단 자체가
     * 부재하므로, 사양의 빈칸을 채워 판정했다(보고서 ② 참조).
     */
    @Test
    void 대기_중인_연결_요청이_없으면_코드를_만들_수_없다() throws Exception {
        코드_생성(STUDENT_B1_ACCOUNT, ACADEMY_B)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LINK_REQUEST_NOT_FOUND"));
    }

    /** 학생 레코드가 없는 계정(학부모)은 코드를 만들 수 없다(§3.3 권한 학생). */
    @Test
    void 학생이_아닌_계정의_코드_생성은_403_FORBIDDEN_이다() throws Exception {
        코드_생성(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── ③ 코드 입력 (P-02, §3.4) ─────────────────────────────────────────

    /** 올바른 코드는 {@code guardian_student} 행을 만든다(§3.4). */
    @Test
    void 학부모가_올바른_코드를_입력하면_guardian_student_행이_생긴다() throws Exception {
        String code = 요청하고_코드를_받는다();

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(STUDENT_A4_ID)))
                .andExpect(jsonPath("$.data.name").value(학생_이름(STUDENT_A4_ID)));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ?"
                        + " AND unlinked_at IS NULL", Integer.class,
                보호자_식별자(GUARDIAN_SIBLINGS_ACCOUNT), STUDENT_A4_ID))
                .as("연결이 실제로 생겨야 한다 — 응답만 보면 아무것도 저장하지 않는 구현과 같다")
                .isEqualTo(1);
    }

    /** 성립한 연결은 {@code link_code.used_at} 을 채운다 — 재사용 차단의 유일한 근거다(ERD). */
    @Test
    void 연결이_성립하면_코드가_사용_처리된다() throws Exception {
        String code = 요청하고_코드를_받는다();

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code).andExpect(status().isCreated());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_at FROM link_code WHERE code = ?", Timestamp.class, code))
                .as("used_at 이 비어 있으면 같은 코드로 몇 번이든 다시 연결된다")
                .isNotNull();
    }

    /**
     * 만료 경계 — 만료 시각이 <b>정확히 현재</b>인 코드는 아직 유효하다.
     *
     * <p>아래 "1초 지난 코드는 거부" 와 짝이다. 한쪽만 두면 판정 경계가 어느 쪽으로든 한 칸 밀려도
     * 통과한다 — 넓히면 이 단언이, 좁히면 저 단언이 문다.
     */
    @Test
    void 만료_시각이_정확히_현재인_코드는_아직_유효하다() throws Exception {
        String code = 요청하고_코드를_받는다();
        코드_만료를_옮긴다(code, OffsetDateTime.now(clock));

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code).andExpect(status().isCreated());
    }

    /** 만료된 코드는 {@code 403 LINK_CODE_INVALID}(§3.4 — 만료·불일치 공통). */
    @Test
    void 만료된_코드를_입력하면_403_LINK_CODE_INVALID_이다() throws Exception {
        String code = 요청하고_코드를_받는다();
        코드_만료를_옮긴다(code, OffsetDateTime.now(clock).minusSeconds(1));

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("LINK_CODE_INVALID"));

        entityManager.flush();
        assertThat(연결_수(GUARDIAN_SIBLINGS_ACCOUNT, STUDENT_A4_ID))
                .as("거부했다면 연결이 생기면 안 된다")
                .isEqualTo(0);
    }

    /** 불일치 코드도 같은 {@code 403 LINK_CODE_INVALID} 다 — 만료와 갈라 답하면 존재 여부가 샌다. */
    @Test
    void 불일치_코드를_입력하면_403_LINK_CODE_INVALID_이다() throws Exception {
        요청하고_코드를_받는다();

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, "000000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("LINK_CODE_INVALID"));
    }

    /**
     * 이미 쓴 코드는 다시 통하지 않는다 — {@code 403 LINK_CODE_INVALID}({@code used_at} 근거).
     *
     * <p>거부 코드가 {@code 409 ALREADY_LINKED} 가 아니라 {@code 403} 인 것까지 본다. 재사용 판정이
     * 연결 여부 판정보다 <b>먼저</b> 와야, 한 번 유출된 코드를 쥔 제3자가 "이 코드는 유효한데 이미
     * 연결됐다" 를 알아내지 못한다.
     */
    @Test
    void 이미_사용된_코드를_다시_입력하면_403_LINK_CODE_INVALID_이다() throws Exception {
        String code = 요청하고_코드를_받는다();
        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code).andExpect(status().isCreated());

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("LINK_CODE_INVALID"));

        entityManager.flush();
        assertThat(연결_수(GUARDIAN_SIBLINGS_ACCOUNT, STUDENT_A4_ID))
                .as("재사용이 통하면 같은 연결이 두 번 생기거나 제약 위반으로 500 이 된다")
                .isEqualTo(1);
    }

    /**
     * 코드는 <b>그 코드를 받은 보호자</b>에게만 통한다 — 남이 넣으면 {@code 403 LINK_CODE_INVALID}.
     *
     * <p>대조를 코드 문자열만으로 하면 6자리 숫자를 훑는 다른 학부모가 남의 자녀를 가져간다. 이
     * 단언이 없으면 "코드가 맞으면 누구든 연결" 인 구현이 나머지 단언을 전부 지나간다.
     */
    @Test
    void 다른_보호자가_남의_코드를_입력하면_403_LINK_CODE_INVALID_이다() throws Exception {
        String code = 요청하고_코드를_받는다();

        코드_입력(OTHER_GUARDIAN_ACCOUNT, code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("LINK_CODE_INVALID"));

        entityManager.flush();
        assertThat(연결_수(OTHER_GUARDIAN_ACCOUNT, STUDENT_A4_ID))
                .as("남의 코드로 연결이 생기면 안 된다")
                .isEqualTo(0);
    }

    /**
     * 코드는 멀쩡한데 이미 연결된 자녀면 {@code 409 ALREADY_LINKED}(§3.4).
     *
     * <p>요청 두 건을 각각 코드까지 받아 두고 첫 코드로 연결한 뒤 <b>둘째 코드</b>를 넣는다 — 그래야
     * 재사용({@code 403})과 갈린 자리가 실제로 검사된다.
     */
    @Test
    void 코드는_유효한데_이미_연결된_자녀면_409_ALREADY_LINKED_이다() throws Exception {
        String first = 요청하고_코드를_받는다();
        String second = 요청하고_코드를_받는다();

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, first).andExpect(status().isCreated());

        코드_입력(GUARDIAN_SIBLINGS_ACCOUNT, second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_LINKED"));
    }

    // ── 자녀 목록 (ATT-03, §3.1) ─────────────────────────────────────────

    /** 목록에는 <b>내 자녀만</b> 나온다(§1.5 — 학부모는 연결된 자녀 범위). */
    @Test
    void 연결된_자녀만_자녀_목록에_등장한다() throws Exception {
        mockMvc.perform(get(CHILDREN).header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A,
                        Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_1_ID)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_2_ID)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(UNLINKED_STUDENT_ID))
                        .isEmpty());
    }

    /** 목록 항목은 §3.1 이 정한 4개다 — 이름은 알림 문구에 필수라 비어 있으면 안 된다. */
    @Test
    void 자녀_목록_항목은_식별_선택에_필요한_최소값만_담는다() throws Exception {
        mockMvc.perform(get(CHILDREN).header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A,
                        Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')].name".formatted(SIBLING_1_ID))
                        .value(학생_이름(SIBLING_1_ID)))
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')].linked_at".formatted(SIBLING_1_ID))
                        .isNotEmpty());
    }

    /**
     * 연결이 해제된 자녀({@code unlinked_at})는 목록에서 빠진다(ERD {@code guardian_student}).
     *
     * <p>남아 있으면 퇴원으로 관계가 끝난 뒤에도 옛 보호자가 자녀 정보를 계속 조회한다.
     */
    @Test
    void 연결_해제된_자녀는_자녀_목록에_등장하지_않는다() throws Exception {
        jdbcTemplate.update("UPDATE guardian_student SET unlinked_at = now() WHERE guardian_id = ?"
                + " AND student_id = ?", 보호자_식별자(GUARDIAN_SIBLINGS_ACCOUNT), SIBLING_1_ID);

        mockMvc.perform(get(CHILDREN).header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A,
                        Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_1_ID)).isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(SIBLING_2_ID)).isNotEmpty());
    }

    /**
     * {@code photo_url} 은 학부모·학생 앱 응답에 <b>부재</b>한다(§1.12 · ERD {@code student}).
     *
     * <p>학생에 사진을 실제로 넣어 두고 본다 — 값이 비어 있으면 필드를 담는 구현도 {@code null} 로
     * 나가 아무것도 검사되지 않는다. 응답 본문 전체에서 그 이름 자체가 없는 것까지 확인한다.
     */
    @Test
    void 자녀_목록_응답에_photo_url_이_부재한다() throws Exception {
        jdbcTemplate.update("UPDATE student SET photo_url = ? WHERE id = ?", "https://x/p5t3.jpg", SIBLING_1_ID);

        MvcResult result = mockMvc.perform(get(CHILDREN)
                        .header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, Role.PARENT)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(본문(result))
                .as("사진은 매니저 앱·관계자 웹·메인 관리자 콘솔에만 반환한다(§1.12)")
                .doesNotContain("photo_url")
                .doesNotContain("p5t3.jpg");
    }

    /** 연결된 자녀가 없으면 빈 {@code items[]} 다(§3.1) — 오류가 아니다. */
    @Test
    void 연결된_자녀가_없으면_빈_목록이다() throws Exception {
        jdbcTemplate.update("UPDATE guardian_student SET unlinked_at = now() WHERE guardian_id = ?",
                보호자_식별자(GUARDIAN_SIBLINGS_ACCOUNT));

        mockMvc.perform(get(CHILDREN).header("Authorization", 토큰(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A,
                        Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /** 보호자 레코드가 없는 계정은 자녀 목록에 닿을 수 없다(§3.1 권한 학부모). */
    @Test
    void 보호자가_아닌_계정의_자녀_목록_조회는_403_FORBIDDEN_이다() throws Exception {
        mockMvc.perform(get(CHILDREN).header("Authorization", 토큰(13L, ACADEMY_A, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 토큰(long accountId, Long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private ResultActions 연결_요청(long guardianAccountId,
            String studentLoginId) throws Exception {
        return mockMvc.perform(post(LINK_REQUESTS)
                .header("Authorization", 토큰(guardianAccountId, ACADEMY_A, Role.PARENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"student_login_id\":\"%s\"}".formatted(studentLoginId)));
    }

    private long 연결을_요청한다(long guardianAccountId, String studentLoginId) throws Exception {
        MvcResult result = 연결_요청(guardianAccountId, studentLoginId)
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.link_request_id")).longValue();
    }

    private ResultActions 코드_생성(long studentAccountId, Long academyId)
            throws Exception {
        return mockMvc.perform(post(LINK_CODE)
                .header("Authorization", 토큰(studentAccountId, academyId, Role.STUDENT)));
    }

    private ResultActions 코드_입력(long guardianAccountId, String code)
            throws Exception {
        return mockMvc.perform(post(LINK)
                .header("Authorization", 토큰(guardianAccountId, ACADEMY_A, Role.PARENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code)));
    }

    /** ①②를 한 번에 밟아 학부모가 넣을 코드를 얻는다 — ③만 검사하는 시험들의 공통 재료다. */
    private String 요청하고_코드를_받는다() throws Exception {
        연결을_요청한다(GUARDIAN_SIBLINGS_ACCOUNT, SeedFixtures.STUDENT_A4_LOGIN_ID);
        MvcResult result = 코드_생성(STUDENT_A4_ACCOUNT, ACADEMY_A)
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(본문(result), "$.data.code");
    }

    /**
     * 발급된 코드의 만료 시각을 원하는 순간으로 옮긴다 — 고정 시계를 쓰므로 "기다린다" 는 수단이
     * 부재하고, 시계를 옮기면 발급 시각까지 함께 움직여 무엇을 검사했는지 흐려진다.
     *
     * <p><b>{@code clear()} 가 이 도우미의 핵심이다.</b> 방금 발급한 {@code LinkCode} 는 이 테스트
     * 트랜잭션의 영속성 컨텍스트에 남아 있어, SQL 로 값을 바꿔도 다음 조회가 <b>바뀌기 전 엔티티</b>를
     * 그대로 돌려준다. 지우지 않으면 만료 단언이 옛 만료 시각을 보고 통과해 <b>아무것도 검사하지
     * 않는다</b> — 실제로 이 자리에서 그 형태로 한 번 초록이 났다.
     */
    private void 코드_만료를_옮긴다(String code, OffsetDateTime expiresAt) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE link_code SET expires_at = ? WHERE code = ?",
                Timestamp.from(expiresAt.toInstant()), code);
        entityManager.clear();
    }

    private int 요청_수(long guardianAccountId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM link_request WHERE guardian_id = ?",
                Integer.class, 보호자_식별자(guardianAccountId));
    }

    private int 연결_수(long guardianAccountId, long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ?",
                Integer.class, 보호자_식별자(guardianAccountId), studentId);
    }

    private long 보호자_식별자(long accountId) {
        return jdbcTemplate.queryForObject("SELECT id FROM guardian WHERE account_id = ?", Long.class, accountId);
    }

    private String 학생_이름(long studentId) {
        return jdbcTemplate.queryForObject("SELECT name FROM student WHERE id = ?", String.class, studentId);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
