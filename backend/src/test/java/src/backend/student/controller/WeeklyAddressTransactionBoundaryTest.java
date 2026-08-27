package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 요일별 주소 저장의 <b>원자성</b>을 커밋 경계 밖에서 확인한다(§3.7 저장 보류 · API_SPEC).
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 없는 것이 요점이다.</b> {@code WeeklyAddressControllerTest}
 * 는 클래스 레벨 트랜잭션 안에서 돌아 아무것도 커밋하지 않으므로, 프로덕션의
 * {@code WeeklyAddressStore.apply()} 에서 {@code @Transactional} 을 <b>지워도</b> 그 15건이 전부
 * 초록이다 — 게이트 리뷰가 직접 심어 확인한 사실이다. 즉 "부분 저장이 남지 않는다" 는 약속이
 * 그 묶음에서는 무단언이었다.
 *
 * <p>여기서 보는 사고는 이렇다. 한 요청이 여러 칸을 담았고 <b>뒤쪽 칸</b>이 DB 제약에 걸린다.
 * 저장이 한 트랜잭션이 아니면 앞쪽 칸은 이미 커밋된 뒤라, 사용자는 {@code 409} 를 받고 화면에는
 * 절반만 반영된 주소가 남는다. 어느 것이 현재 값인지 판정할 수단이 사라지고, 그 상태가 Phase 6
 * 노선 계산의 입력이 된다.
 *
 * <p>클래스를 따로 두는 이유는 <b>한 클래스가 트랜잭션 안과 밖을 동시에 가질 수 없어서</b>다.
 * 커밋을 실제로 일으키므로 뒷정리를 손으로 한다 — 롤백이 대신해 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyAddressTransactionBoundaryTest {

    private static final String WEEKLY_ADDRESS = "/api/v1/students/%d/weekly-address";

    /** 학원 B 보호자({@code parentB1}) — {@link #ACADEMY_B_STUDENT_ID} 의 보호자다. */
    private static final long ACADEMY_B_GUARDIAN_ACCOUNT = 9L;

    /** 학원 B 학생({@code studentB1}) — 시드에 요일별 주소가 부재해 이 클래스가 남기는 행만 센다. */
    private static final long ACADEMY_B_STUDENT_ID = 6L;

    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    private static final String ADDRESS = "서울시 경계시험로 100";

    private static final String OTHER_ADDRESS = "서울시 경계시험로 130";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 이 클래스가 만든 승하차지만 지우기 위한 기준선 — 시드와 다른 테스트의 행을 건드리지 않는다. */
    private Long 기존_최대_승하차지;

    @BeforeEach
    void 기준선을_잡는다() {
        기존_최대_승하차지 = jdbcTemplate.queryForObject("SELECT coalesce(max(id), 0) FROM stop", Long.class);
    }

    /** 롤백이 없으므로 손으로 되돌린다 — {@code weekly_address} 가 {@code stop} 을 참조해 순서를 지킨다. */
    @AfterEach
    void 남긴_행을_지운다() {
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id = ?", ACADEMY_B_STUDENT_ID);
        jdbcTemplate.update("DELETE FROM stop WHERE id > ?", 기존_최대_승하차지);
    }

    /**
     * 이 클래스가 <b>정말로</b> 트랜잭션 밖에서 도는지 먼저 고정한다.
     *
     * <p>없으면 다음 사람이 "다른 테스트와 맞춘다" 며 클래스 레벨 {@code @Transactional} 을 붙이는
     * 순간 아래 단언이 조용히 무의미해진다 — 그 상태에서도 초록이라 아무도 모른다. 이 클래스가
     * 존재하는 이유 자체가 그 형태를 겪었기 때문이다.
     */
    @Test
    void 이_클래스는_트랜잭션_밖에서_돈다() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("테스트가 트랜잭션 안에서 돌면 프로덕션의 커밋 경계를 관측할 수단이 사라진다")
                .isFalse();
    }

    /**
     * 뒤쪽 칸이 실패하면 <b>앞쪽 칸도 남지 않는다</b> — 저장 전체가 한 트랜잭션이라는 뜻이다.
     *
     * <p>같은 칸을 두 번 담은 요청은 DB UNIQUE 에 걸려 {@code 409} 가 된다. 그 앞에 성공하는 칸을
     * 하나 두는 것이 이 시험의 전부다 — 앞 칸이 남으면 저장이 칸마다 개별 커밋되고 있는 것이다.
     */
    @Test
    void 뒤쪽_칸이_제약에_걸리면_앞쪽_칸도_커밋되지_않는다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("mon", "to_academy", ADDRESS),
                                항목("tue", "to_academy", ADDRESS),
                                항목("tue", "to_academy", OTHER_ADDRESS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_WEEKLY_ADDRESS"));

        assertThat(행_수())
                .as("409 를 받았는데 앞쪽 칸이 커밋돼 남았다 — 저장이 한 트랜잭션이 아니라 칸마다 개별 커밋이다")
                .isZero();
    }

    /**
     * 성공한 저장은 <b>커밋된다</b> — 위 단언이 "아무것도 저장되지 않는 구현" 을 통과시키지 않게 하는
     * 대조군이다. 이 축이 없으면 저장 자체가 깨져도 원자성 단언은 초록으로 남는다.
     */
    @Test
    void 성공한_저장은_커밋되어_남는다() throws Exception {
        mockMvc.perform(patch(WEEKLY_ADDRESS.formatted(ACADEMY_B_STUDENT_ID))
                        .header("Authorization", 토큰(ACADEMY_B_GUARDIAN_ACCOUNT, ACADEMY_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(본문(항목("mon", "to_academy", ADDRESS), 항목("tue", "to_academy", OTHER_ADDRESS))))
                .andExpect(status().isOk());

        assertThat(행_수()).isEqualTo(2);
    }

    // ── 보조 ─────────────────────────────────────────────────────────────

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

    private int 행_수() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_address WHERE student_id = ?", Integer.class, ACADEMY_B_STUDENT_ID);
    }
}
