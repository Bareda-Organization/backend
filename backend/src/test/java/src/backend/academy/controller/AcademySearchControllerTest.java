package src.backend.academy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
import src.backend.academy.query.AcademySearchQueryService;
import src.backend.academy.repository.AcademyRepository;

/**
 * §2.1 {@code GET /academies/search} — AUTH-02.
 *
 * <p>{@code inactive} 학원은 {@link Academy} 의 정적 팩토리로 만들 수 없다({@code register(...)} 는 항상
 * {@code active} 로 시작) — 그 상태 전이(ACAD-04 비활성화)는 이 태스크 범위 밖이라 팩토리를 늘리지 않고,
 * {@code @Sql} 로 고정 상태 행을 직접 심는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AcademySearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Test
    @Sql(statements = "INSERT INTO academy (code, name, region, status) "
            + "VALUES ('P2T3INAQQQQ', '학원P2T3QQQQ비활성', '서울', 'inactive')")
    void 학원_검색은_inactive_학원을_반환하지_않는다() throws Exception {
        academyRepository.save(Academy.register("P2T3ACTQQQQ", "학원P2T3QQQQ활성", "서울", null, null));

        mockMvc.perform(get("/api/v1/academies/search").param("q", "P2T3QQQQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("P2T3ACTQQQQ"));
    }

    @Test
    void q_파라미터가_없으면_422_VALIDATION_FAILED_이다() throws Exception {
        mockMvc.perform(get("/api/v1/academies/search"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 검색어 {@code "%"} 는 LIKE 와일드카드가 아니라 리터럴 문자로 취급돼야 한다(보완 리뷰 Important #3,
     * C-4) — 이스케이프가 없으면 {@code q="%"} 가 전체 활성 학원과 매칭돼 비인증 엔드포인트로 전체
     * 목록을 열람하는 통로가 된다. 이름에 리터럴 {@code %} 를 포함한 학원은 매칭되고, 포함하지 않은
     * 학원은 매칭되지 않아야 이스케이프가 실제로 걸렸다는 증거가 된다 — 둘 다 확인해야 "우연히 전부
     * 매칭됨"과 구별된다.
     */
    @Test
    void 검색어_퍼센트는_와일드카드가_아니라_리터럴_문자로_취급된다() throws Exception {
        academyRepository.save(Academy.register("P2T3PCT1QQQ", "학원%퍼센트포함QQQQ", "서울", null, null));
        academyRepository.save(Academy.register("P2T3PCT2QQQ", "학원퍼센트미포함QQQQ", "서울", null, null));

        mockMvc.perform(get("/api/v1/academies/search").param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.code == 'P2T3PCT1QQQ')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.code == 'P2T3PCT2QQQ')]").doesNotExist());
    }

    /**
     * 검색 결과는 {@link AcademySearchQueryService} 의 {@code MAX_RESULTS} 를 넘지 않아야 한다
     * (보완 리뷰 Important #3, C-4) — 비인증 공개 엔드포인트라 상한이 없으면 {@code q} 를 넓게 잡아
     * 전체 활성 학원 목록을 한 번에 끌어낼 수 있다. 상한값은 하드코딩하지 않고 리플렉션으로 원본
     * 상수를 그대로 읽어, 상수가 바뀌어도 이 테스트가 따라간다.
     */
    @Test
    void 검색_결과는_MAX_RESULTS_를_넘지_않는다() throws Exception {
        int maxResults = (int) ReflectionTestUtils.getField(AcademySearchQueryService.class, "MAX_RESULTS");
        for (int i = 0; i < maxResults + 5; i++) {
            String suffix = String.format("%02d", i);
            academyRepository.save(
                    Academy.register("P2T3CAP" + suffix + "Q", "학원상한테스트QQQQ" + suffix, "서울", null, null));
        }

        mockMvc.perform(get("/api/v1/academies/search").param("q", "상한테스트QQQQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(maxResults));
    }
}
