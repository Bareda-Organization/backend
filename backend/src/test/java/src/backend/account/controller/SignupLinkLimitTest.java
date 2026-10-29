package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 가입 수락의 {@code link.student_ids[]} 는 20개까지다(API_SPEC §5.2) — 넘으면 422 이고, 받은 id 는 학생 조회 한 번으로 찾는다
 * (id 마다 조회하면 수락 트랜잭션의 행 잠금이 열린 채로 질의가 늘어난다).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class SignupLinkLimitTest {

    /** 시드 {@code signup_request} 2번 — 학원 A 학부모 {@code parentPending} 의 가입 요청. */
    private static final long PARENT_REQUEST = 2L;

    private static final long STAFF_A_ACCOUNT = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 학생_id_가_스물한_개이면_422_VALIDATION_FAILED_이다() throws Exception {
        처리한다(수락_본문(LongStream.rangeClosed(9001, 9021).toArray()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 학생_id_가_스무_개이면_검증을_통과해_없는_학생으로_404_이다() throws Exception {
        처리한다(수락_본문(LongStream.rangeClosed(9001, 9020).toArray()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void 학생_id_를_여럿_주어도_학생_조회는_한_번이다() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        처리한다(수락_본문(1L, 2L)).andExpect(status().isOk());

        long studentQueries = Arrays.stream(statistics.getQueries())
                .filter(hql -> hql.toUpperCase().contains("FROM STUDENT "))
                .mapToLong(hql -> statistics.getQueryStatistics(hql).getExecutionCount())
                .sum();
        assertThat(studentQueries).isEqualTo(1);
    }

    private ResultActions 처리한다(String body) throws Exception {
        String token = "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT, 1L, Role.STAFF,
                AccountStatus.ACTIVE);
        return mockMvc.perform(post("/api/v1/staff/signup-requests/" + PARENT_REQUEST + "/decide")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String 수락_본문(long... studentIds) {
        String ids = Arrays.stream(studentIds).mapToObj(Long::toString).collect(Collectors.joining(", "));
        return "{\"accept\": true, \"link\": {\"student_ids\": [%s]}}".formatted(ids);
    }
}
