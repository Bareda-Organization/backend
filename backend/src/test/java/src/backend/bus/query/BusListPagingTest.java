package src.backend.bus.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.관계자_토큰;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.bus.repository.BusRepository;
import src.backend.global.security.JwtTokenProvider;
import src.backend.run.controller.DriverRunFixtures;

/**
 * 차량 목록의 페이지 이동(API_SPEC §5.12) — 정렬 값이 같은 차량이 많아도(정원 16 이 대부분) 페이지를 넘기며 같은 차량이
 * 되풀이되거나 건너뛰어지지 않는다. 값이 같으면 순서가 정해지지 않으므로 마지막 정렬 기준이 {@code id} 여야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusListPagingTest {

    private static final int BUS_COUNT = 40;

    private static final int PAGE_SIZE = 7;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @MockitoSpyBean
    private BusRepository busRepository;

    @Test
    void 정원이_같은_차량이_많아도_페이지를_넘기며_되풀이나_누락이_없다() throws Exception {
        long academyId = 같은_정원_차량을_만든다();

        List<Long> seen = new ArrayList<>();
        for (int page = 0; seen.size() < BUS_COUNT && page < BUS_COUNT; page++) {
            String body = mockMvc.perform(get("/api/v1/staff/buses?sort=capacity&page=%d&size=%d".formatted(page, PAGE_SIZE))
                    .header("Authorization", 관계자_토큰(tokenProvider, academyId)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Object> ids = JsonPath.read(body, "$.data.items[*].id");
            ids.forEach(id -> seen.add(Long.parseLong(id.toString())));
        }

        assertThat(seen).as("모든 차량이 한 번씩만 나와야 한다").hasSize(BUS_COUNT).doesNotHaveDuplicates();
    }

    @Test
    void 정렬_기준_끝에_id_가_붙어_값이_같은_차량의_순서가_정해진다() throws Exception {
        long academyId = 같은_정원_차량을_만든다();

        mockMvc.perform(get("/api/v1/staff/buses?sort=capacity&size=%d".formatted(PAGE_SIZE))
                .header("Authorization", 관계자_토큰(tokenProvider, academyId))).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(busRepository, atLeastOnce()).findAllByAcademyId(eq(academyId), pageable.capture());
        assertThat(pageable.getValue().getSort().stream().map(order -> order.getProperty()).toList())
                .as("요청한 정렬 뒤에 id 가 붙어야 한다").containsExactly("capacity", "id");
    }

    private long 같은_정원_차량을_만든다() {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        for (int i = 0; i < BUS_COUNT; i++) {
            fixtures.bus(academyId);
        }
        return academyId;
    }
}
