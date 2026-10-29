package src.backend.manager.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.web.TestResponses.본문;

import java.util.ArrayList;
import java.util.List;

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

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 매니저 목록(§5.13)은 정렬 값이 겹쳐도 페이지를 넘기며 같은 매니저가 두 번 나오거나 빠지지 않는다 — {@code sort=role} 처럼 값이 둘뿐인
 * 정렬과 동명이인이 있는 기본 정렬({@code name}) 모두. 동점 행의 순서를 DB 에 맡기면 페이지마다 다른 순서로 잘린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManagerListPagingTest {

    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final int MANAGER_COUNT = 9;

    private static final String SAME_NAME = "동명이인페이징";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void 역할로_정렬해_한_건씩_넘겨도_모든_매니저가_한_번씩_나온다() throws Exception {
        List<Long> created = 겹치는_매니저를_만든다();

        List<Long> paged = 페이지로_모은다("sort=role", 2);

        assertThat(paged).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();
        assertThat(paged).as("같은 역할 안에서는 id 순").isEqualTo(
                jdbcTemplate.queryForList("SELECT id FROM manager WHERE name = ? ORDER BY role, id", Long.class,
                        SAME_NAME));
    }

    @Test
    void 동명이인이_있는_기본_정렬도_페이지를_넘겨도_겹치거나_빠지지_않는다() throws Exception {
        List<Long> created = 겹치는_매니저를_만든다();

        List<Long> paged = 페이지로_모은다("sort=name", 2);

        assertThat(paged).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();
        assertThat(paged).isSorted();
    }

    /** 이름이 같고 역할이 번갈아 나오는 매니저를 만든 뒤 일부 행을 고쳐 힙 위치(물리 순서)를 id 순서와 어긋나게 한다. */
    private List<Long> 겹치는_매니저를_만든다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < MANAGER_COUNT; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/staff/managers").header("Authorization", 토큰())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"%s\",\"phone\":\"010-9600-%04d\",\"role\":\"%s\"}"
                                    .formatted(SAME_NAME, i, i % 2 == 0 ? "driver" : "escort")))
                    .andExpect(status().isCreated()).andReturn();
            ids.add(Long.parseLong(JsonPath.read(본문(result), "$.data.id")));
        }
        for (int i = MANAGER_COUNT - 1; i >= 0; i -= 2) {
            jdbcTemplate.update("UPDATE manager SET phone = phone WHERE id = ?", ids.get(i));
        }
        return ids;
    }

    private List<Long> 페이지로_모은다(String sort, int size) throws Exception {
        List<Long> collected = new ArrayList<>();
        for (int page = 0; page * size < MANAGER_COUNT + size; page++) {
            MvcResult result = mockMvc.perform(get("/api/v1/staff/managers?q=%s&%s&size=%d&page=%d"
                            .formatted(SAME_NAME, sort, size, page)).header("Authorization", 토큰()))
                    .andExpect(status().isOk()).andReturn();
            List<String> pageIds = JsonPath.read(본문(result), "$.data.items[*].id");
            pageIds.forEach(id -> collected.add(Long.parseLong(id)));
        }
        return collected;
    }

    private String 토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
