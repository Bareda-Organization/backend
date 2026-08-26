package src.backend.bus.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /** 페이징 단언이 쓰는 페이지 크기 — 학원 A 의 실제 차량 수보다 작아야 다음 페이지가 생긴다. */
    private static final int PAGE_SIZE = 2;

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

    /**
     * 등록 요청의 {@code operable} 이 <b>실제로 저장</b>된다 — 응답만 보지 않고 목록으로 다시 읽는다.
     *
     * <p>등록과 수정은 다른 경로다. 수정 경로의 {@code operable} 만 검사하면 등록 시 그 값을 반영하는
     * 호출을 통째로 지워도 아무 단언에도 걸리지 않는다(게이트 리뷰 A3 가 실증) — 그 상태에서는 정비
     * 중인 차량을 운행 불가로 등록해도 운행 가능으로 들어가고, Phase 7 확정 배치가 그 차량을 편성한다.
     *
     * <p>목록으로 되읽는 단언이 응답 단언과 별개 축이다 — 응답만 보면 저장하지 않고 요청값을 그대로
     * 되돌려주는 구현이 통과한다.
     */
    @Test
    void 운행_불가로_등록하면_그_값이_저장된다() throws Exception {
        MvcResult result = 등록한다(관계자A_토큰(),
                "{\"bus_no\":\"정비중호차\",\"plate_no\":\"12나1200\",\"capacity\":16,\"operable\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operable").value(false))
                .andReturn();
        long busId = ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();

        assertThat(JsonPath.<List<Boolean>>read(목록_본문(관계자A_토큰()),
                "$.data.items[?(@.id == %d)].operable".formatted(busId)))
                .as("등록 응답만 맞고 저장이 안 되면 목록에서 운행 가능으로 되살아난다")
                .containsExactly(false);
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

    // ── 호차 중복 (Ruling 164) ────────────────────────────────────────────

    /**
     * 같은 학원에 같은 호차를 다시 등록하면 {@code 409 DUPLICATE_BUS_NO} 다(§5.12 · Ruling 164).
     *
     * <p>{@code 500} 이 아닌 것이 이 단언의 내용이다 — {@code uk_bus_academy_bus_no} 위반이 예외 번역을
     * 거치지 않으면 그대로 {@code INTERNAL_ERROR} 가 나가고, 사용자는 "서버가 고장났다" 와 "이미 있는
     * 호차다" 를 구별할 수단을 잃는다. 게이트 리뷰가 프로브로 그 형태를 실측했다.
     */
    @Test
    void 같은_학원에_같은_호차를_다시_등록하면_409_DUPLICATE_BUS_NO_이다() throws Exception {
        등록한다(관계자A_토큰(), "{\"bus_no\":\"중복호차\",\"plate_no\":\"10가1000\",\"capacity\":16}")
                .andExpect(status().isOk());

        등록한다(관계자A_토큰(), "{\"bus_no\":\"중복호차\",\"plate_no\":\"10가2000\",\"capacity\":16}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_BUS_NO"));
    }

    /**
     * 다른 학원은 같은 호차를 쓸 수 있다 — 유일성의 범위가 {@code (academy_id, bus_no)} 이기 때문이다.
     *
     * <p>이 단언이 없으면 중복 검사를 학원 조건 없이 거는 구현이 통과하고, 그러면 <b>다른 학원이
     * 먼저 쓴 호차를 못 쓰게 되어</b> 격리가 반대 방향으로 샌다.
     */
    @Test
    void 다른_학원은_같은_호차를_쓸_수_있다() throws Exception {
        등록한다(관계자A_토큰(), "{\"bus_no\":\"공용호차\",\"plate_no\":\"10가3000\",\"capacity\":16}")
                .andExpect(status().isOk());

        등록한다(관계자B_토큰(), "{\"bus_no\":\"공용호차\",\"plate_no\":\"10가4000\",\"capacity\":16}")
                .andExpect(status().isOk());
    }

    /**
     * 수정으로 기존 호차와 겹쳐도 {@code 409 DUPLICATE_BUS_NO} 다 — 등록만 막고 수정을 열어 두면
     * 같은 {@code 500} 이 그 경로로 그대로 나간다.
     *
     * <p>수정 경로는 등록과 달리 <b>변경 감지</b>로 DB 에 닿아 UPDATE 가 커밋 시점까지 미뤄진다 —
     * 번역 지점 안에서 flush 하지 않으면 제약 위반이 그 밖에서 터진다(`AcademyStaffQuota` 가 같은
     * 자리에서 실측한 형태).
     */
    @Test
    void 호차_수정으로_같은_학원의_기존_호차와_겹치면_409_DUPLICATE_BUS_NO_이다() throws Exception {
        등록한다(관계자A_토큰(), "{\"bus_no\":\"선점호차\",\"plate_no\":\"10가5000\",\"capacity\":16}")
                .andExpect(status().isOk());
        long busId = 등록된_차량_id(관계자A_토큰(), "바꿀호차", "10가6000", 16);

        수정한다(관계자A_토큰(), busId, "{\"bus_no\":\"선점호차\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_BUS_NO"));
    }

    /** 자기 호차를 그대로 다시 보내는 수정은 통과한다 — 중복 검사가 자기 자신을 세면 이름을 못 고친다. */
    @Test
    void 자기_호차를_그대로_보내는_수정은_통과한다() throws Exception {
        long busId = 등록된_차량_id(관계자A_토큰(), "유지호차", "10가7000", 16);

        수정한다(관계자A_토큰(), busId, "{\"bus_no\":\"유지호차\",\"capacity\":20}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bus_no").value("유지호차"))
                .andExpect(jsonPath("$.data.capacity").value(20));
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

    /**
     * 요청한 {@code size} 가 <b>실제로 반환 건수를 제한</b>하고, 다음 페이지가 나머지를 담는다(§1.8).
     *
     * <p>목록 응답의 {@code size} 필드만 보면 요청값이 그대로 되돌아올 뿐이라, 페이지 요청을 통째로
     * 무시하고 전건을 반환하는 구현과 구별되지 않는다(게이트 리뷰 A1 이 실증). 전건 반환은 에러 없이
     * 동작해서 화면은 멀쩡해 보이는데, 학원의 차량이 늘수록 한 응답이 계속 무거워진다.
     *
     * <p>단언 넷이 각각 다른 사고를 막는다 — 건수 제한 · {@code total_count} 가 페이지 크기와 다름 ·
     * {@code has_next} · <b>다음 페이지가 앞 페이지와 겹치지 않음</b>. 마지막이 없으면 매 페이지가
     * 같은 앞부분을 돌려주는 구현이 통과하고, 사용자는 뒤쪽 차량에 영영 닿지 못한다.
     */
    @Test
    void 차량_목록은_요청한_size_보다_많은_행을_반환하지_않고_다음_페이지가_나머지를_담는다() throws Exception {
        등록된_차량_id(관계자A_토큰(), "페이징1호차", "20가2001", 16);
        등록된_차량_id(관계자A_토큰(), "페이징2호차", "20가2002", 16);

        String 첫_페이지 = 페이지_본문(관계자A_토큰(), 0, PAGE_SIZE);
        String 둘째_페이지 = 페이지_본문(관계자A_토큰(), 1, PAGE_SIZE);

        assertThat(JsonPath.<List<Object>>read(첫_페이지, "$.data.items"))
                .as("요청 size 를 무시하고 전건을 반환하면 안 된다").hasSize(PAGE_SIZE);
        assertThat(JsonPath.<Integer>read(첫_페이지, "$.data.total_count"))
                .as("total_count 는 한 페이지 건수가 아니라 전체 행 수다").isGreaterThan(PAGE_SIZE);
        assertThat(JsonPath.<Boolean>read(첫_페이지, "$.data.has_next")).isTrue();
        assertThat(JsonPath.<List<Integer>>read(둘째_페이지, "$.data.items[*].id"))
                .as("다음 페이지가 앞 페이지와 겹치면 뒤쪽 차량에 닿을 수단이 부재하다")
                .doesNotContainAnyElementsOf(JsonPath.read(첫_페이지, "$.data.items[*].id"));
    }

    /**
     * {@code total_count} 가 <b>실제 행 수</b>를 따른다 — 한 건을 등록하면 정확히 1 늘어난다.
     *
     * <p>{@code size=1} 로 부르는 것이 이 단언의 요점이다. 페이지에 담긴 건수와 총수가 갈리는 상태에서
     * 재야 {@code items.size()} 를 총수로 되돌려주는 위조가 드러난다(게이트 리뷰 A2 가 실증) — 기본
     * 페이지 크기로 부르면 둘이 우연히 같아 위조가 통과한다.
     *
     * <p>총수를 절대값으로 고정하지 않고 <b>증분</b>으로 재는 이유는 시드가 학원 A 에 차량을 이미
     * 넣어 두어, 절대값을 박으면 시드가 바뀔 때 이 테스트가 먼저 틀리기 때문이다.
     */
    @Test
    void 차량_total_count_는_등록한_행_수만큼_증가한다() throws Exception {
        int before = 총수(관계자A_토큰());

        등록된_차량_id(관계자A_토큰(), "총수확인호차", "21가2100", 16);

        assertThat(총수(관계자A_토큰()))
                .as("total_count 가 페이지에 담긴 건수를 되돌려주면 등록해도 값이 움직이지 않는다")
                .isEqualTo(before + 1);
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

    private String 페이지_본문(String token, int page, int size) throws Exception {
        return 본문(mockMvc.perform(get("/api/v1/staff/buses?page=%d&size=%d".formatted(page, size))
                .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn());
    }

    /** {@code size=1} 로 물어 페이지 건수와 총수가 갈린 상태에서 총수를 읽는다. */
    private int 총수(String token) throws Exception {
        return JsonPath.read(페이지_본문(token, 0, 1), "$.data.total_count");
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
