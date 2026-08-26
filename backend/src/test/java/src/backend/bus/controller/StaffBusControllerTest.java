package src.backend.bus.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.bus.entity.BusSeating;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.12 {@code /staff/buses} — BUS-01·02·03 · A-11 (Phase 5 목표 2).
 *
 * <p><b>이 클래스의 최우선 단언은 {@code student_capacity} 가 요청값이 아니라는 것</b>이다. 응답에
 * 계산식과 맞는 값이 실렸는지만 보면 <b>요청값을 그대로 되돌려준 구현</b>과 구별되지 않는다 — 그
 * 상태에서는 클라이언트가 정원을 마음대로 불려 보낼 수 있고, Phase 7 확정 배치가 그 값을 믿는다.
 *
 * <p>정원 초과 <b>배정</b> 차단({@code 409 CAPACITY_EXCEEDED})은 여기 없다 — Phase 5 에 학생을 차량에
 * 배정하는 경로가 부재해(Ruling 155) 그 단언은 아무것도 검사하지 않는 채 초록이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffBusControllerTest {

    /** 시드의 학원 A 관계자({@code staffA}) — 소속 학원이 곧 이 테스트의 범위다. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    /** 시드의 학원 B 관계자({@code staffB}) — 격리 검증에서 남의 학원 차량을 지목하는 쪽이다. */
    private static final long STAFF_B_ACCOUNT_ID = 3L;

    private static final long ACADEMY_B_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    // ── BUS-02 등록 · 정원 계산 (목표 2) ──────────────────────────────────

    /**
     * 목표 2 ① — 등록 응답의 {@code student_capacity} 가 <b>요청에 없던 값</b>이고 계산식과 같다.
     *
     * <p>기댓값을 리터럴이 아니라 {@link BusSeating} 의 기본값으로 적는다 — ERD 가 정한 승무 인원이
     * 바뀌면 이 단언도 함께 움직여야 하고, 리터럴로 박으면 그때 계산식이 아니라 이 테스트가 틀린다.
     */
    @Test
    void 차량을_등록하면_student_capacity_가_정원에서_기사와_동승자_수를_뺀_값으로_저장된다() throws Exception {
        int capacity = 16;

        등록한다(관계자A_토큰(), "{\"bus_no\":\"9호차\",\"plate_no\":\"99가9999\",\"capacity\":%d}".formatted(capacity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(capacity))
                .andExpect(jsonPath("$.data.student_capacity").value(
                        capacity - BusSeating.DEFAULT_DRIVER_COUNT - BusSeating.DEFAULT_ESCORT_COUNT))
                .andExpect(jsonPath("$.data.operable").value(true));
    }

    /**
     * 목표 2 ② — 요청에 {@code student_capacity} 를 실어 보내도 <b>무시</b>되고 서버 계산값이 남는다.
     *
     * <p>보내는 값(99)을 계산값(14)과 크게 벌려 둔다 — 우연히 같은 값이면 무시했는지 받아썼는지
     * 구별되지 않는다. 이 단언이 없으면 요청 DTO 에 필드를 하나 더해 그대로 저장하는 변경이
     * 아무것도 깨뜨리지 않고 통과한다.
     */
    @Test
    void 요청에_student_capacity_를_실어도_서버_계산값으로_저장된다() throws Exception {
        MvcResult result = 등록한다(관계자A_토큰(),
                "{\"bus_no\":\"8호차\",\"plate_no\":\"88가8888\",\"capacity\":16,\"student_capacity\":99}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_capacity").value(14))
                .andReturn();

        assertThat(목록_본문(관계자A_토큰())).as("목록으로 다시 읽어도 요청값이 아니다").doesNotContain("\"student_capacity\":99");
        assertThat(본문(result)).contains("\"student_capacity\":14");
    }

    /**
     * 목표 2 ③ — 학생 정원이 0 이하가 되는 차량은 저장이 차단된다.
     *
     * <p>승무 인원 합과 <b>같은</b> 정원을 쓴다 — 경계값이라, 조건을 {@code <} 로 한 칸 넓힌 구현이
     * 여기서만 드러난다. 이 차량이 저장되면 Phase 7 확정 배치가 0명짜리 버스를 편성한다.
     */
    @Test
    void 정원이_기사와_동승자_수_이하인_차량_등록은_저장이_차단된다() throws Exception {
        int crewOnly = BusSeating.DEFAULT_DRIVER_COUNT + BusSeating.DEFAULT_ESCORT_COUNT;

        등록한다(관계자A_토큰(), "{\"bus_no\":\"7호차\",\"plate_no\":\"77가7777\",\"capacity\":%d}".formatted(crewOnly))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(목록_본문(관계자A_토큰())).as("차단된 등록이 목록에 남으면 안 된다").doesNotContain("7호차");
    }

    // ── BUS-03 수정 ───────────────────────────────────────────────────────

    /** 정원을 고치면 학생 정원이 <b>다시 계산</b>된다 — 두 값이 어긋난 채로 남으면 DB CHECK 가 UPDATE 를 거부한다. */
    @Test
    void 정원을_수정하면_student_capacity_가_함께_다시_계산된다() throws Exception {
        long busId = 등록된_차량_id(관계자A_토큰(), "6호차", "66가6666", 16);

        수정한다(관계자A_토큰(), busId, "{\"capacity\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(30))
                .andExpect(jsonPath("$.data.student_capacity").value(28));
    }

    /** 수정도 같은 정원 하한을 받는다 — 등록만 막고 수정을 열어 두면 정원을 낮추는 경로로 그대로 우회된다. */
    @Test
    void 정원을_기사와_동승자_수_이하로_낮추는_수정은_차단된다() throws Exception {
        long busId = 등록된_차량_id(관계자A_토큰(), "5호차", "55가5555", 16);

        수정한다(관계자A_토큰(), busId, "{\"capacity\":2}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 운행 가능 여부만 고치는 수정은 정원을 건드리지 않는다 — 보내지 않은 필드는 그대로 남는다(§5.12 PATCH). */
    @Test
    void 운행_가능_여부만_고치면_정원은_그대로_남는다() throws Exception {
        long busId = 등록된_차량_id(관계자A_토큰(), "4호차", "44가4444", 16);

        수정한다(관계자A_토큰(), busId, "{\"operable\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operable").value(false))
                .andExpect(jsonPath("$.data.capacity").value(16))
                .andExpect(jsonPath("$.data.student_capacity").value(14));
    }

    // ── 학원 격리 (규칙 7) ────────────────────────────────────────────────

    /**
     * 다른 학원의 차량을 {@code {id}} 로 지목하면 {@code 404 BUS_NOT_FOUND} 다 — 존재 여부를 흘리지 않는다.
     *
     * <p>학원 조건을 저장소 쿼리에 넣었기 때문에 "없음" 과 "남의 학원" 이 같은 빈 결과가 된다. 꺼낸
     * 뒤에 대조하는 형태로 바꾸면 응답이 갈려 남의 학원 차량의 실재가 드러난다.
     */
    @Test
    void 다른_학원의_차량을_수정하면_404_BUS_NOT_FOUND_이다() throws Exception {
        long busOfAcademyA = 등록된_차량_id(관계자A_토큰(), "3호차", "33가3333", 16);

        수정한다(관계자B_토큰(), busOfAcademyA, "{\"capacity\":30}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUS_NOT_FOUND"));
    }

    /**
     * 목록은 소속 학원의 차량만 싣는다 — 조건이 빠져도 목록은 동작하므로 여기서만 드러난다
     * (ARCHITECTURE §6.1).
     *
     * <p>학원 A 가 만든 차량이 학원 B 의 목록에 없는 것과, 학원 B 자신의 차량은 <b>있는</b> 것을 함께
     * 본다 — 뒤를 빼면 목록이 통째로 비어도 앞 단언이 통과한다.
     */
    @Test
    void 차량_목록은_소속_학원의_차량만_싣는다() throws Exception {
        등록된_차량_id(관계자A_토큰(), "2호차-A전용", "22가2222", 16);

        String bodyOfB = 목록_본문(관계자B_토큰());

        assertThat(bodyOfB).doesNotContain("2호차-A전용");
        assertThat(bodyOfB).as("B 의 목록이 비면 위 부재 단언은 아무것도 검사하지 않는다").contains("\"bus_no\"");
    }

    /** 목록 응답은 §1.8 봉투를 그대로 쓴다 — 항목 배열과 페이지 정보 넷을 함께 싣는다. */
    @Test
    void 차량_목록_응답은_items_와_page_size_total_count_has_next_를_함께_싣는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/buses").header("Authorization", 관계자A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").isNumber())
                .andExpect(jsonPath("$.data.size").isNumber())
                .andExpect(jsonPath("$.data.total_count").isNumber())
                .andExpect(jsonPath("$.data.has_next").isBoolean());
    }

    // ── 호출 도우미 ───────────────────────────────────────────────────────

    private ResultActions 등록한다(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/buses")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions 수정한다(String token, long busId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/staff/buses/" + busId)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long 등록된_차량_id(String token, String busNo, String plateNo, int capacity) throws Exception {
        MvcResult result = 등록한다(token,
                "{\"bus_no\":\"%s\",\"plate_no\":\"%s\",\"capacity\":%d}".formatted(busNo, plateNo, capacity))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private String 목록_본문(String token) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/buses?size=100").header("Authorization", token))
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
