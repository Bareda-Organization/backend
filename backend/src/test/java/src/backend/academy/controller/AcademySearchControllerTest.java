package src.backend.academy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.entity.Academy;
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
}
