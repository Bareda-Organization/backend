package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jayway.jsonpath.JsonPath;

import src.backend.boarding.query.RosterQueryService;
import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.monitoring.query.AdminRunRosterQueryService;
import src.backend.student.query.StudentQueryService;

/**
 * R46 감사 #1·C — 조회 감사는 읽기 트랜잭션 <b>밖에서</b> 기록되고(연결 2개를 동시에 쥐지 않음), 조회 기록에 접속 IP 가
 * 남는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 시험 자체가 트랜잭션을 열면 "기록 시점에 트랜잭션이 없다" 를 볼 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditReadRequestIntegrationTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditRecorder auditRecorder;

    @Test
    void 학생_상세_조회의_감사_기록_시점에_트랜잭션이_없다() throws Exception {
        long studentId = 등록한다("203.0.113.9");
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(auditRecorder).recordDataAccessRead(any(), anyLong(), eq("student"), eq(studentId), any());

        mockMvc.perform(get("/api/v1/staff/students/" + studentId).header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        assertThat(transactionActive.get()).as("감사 호출이 일어났고, 그때 트랜잭션이 없었다").isFalse();
    }

    @Test
    void 조회가_실패하면_감사_기록을_시도하지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/staff/students/" + Long.MAX_VALUE).header("Authorization", 관계자_토큰()))
                .andExpect(status().isNotFound());

        verify(auditRecorder, never()).recordDataAccessRead(any(), anyLong(), any(), any(), any());
        verify(auditRecorder, never()).recordDataAccessReads(any(), anyLong(), any(), any());
    }

    @Test
    void 명단_학생_조회_서비스_3종은_클래스_트랜잭션이_없다() {
        for (Class<?> service : List.of(RosterQueryService.class, AdminRunRosterQueryService.class,
                StudentQueryService.class)) {
            assertThat(service.isAnnotationPresent(Transactional.class))
                    .as("%s — 감사 기록이 읽기 트랜잭션 안에서 돌면 연결 2개를 동시에 쥔다", service.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    void 조회_기록에_프록시가_덮어쓴_X_Real_IP_가_남는다() throws Exception {
        // 등록 응답이 학생 상세를 그대로 돌려주므로(§1.9) 그 요청이 조회 기록 1행을 남긴다
        long studentId = 등록한다("203.0.113.7");

        assertThat(jdbcTemplate.queryForObject(
                "select host(ip) from audit_log where target_type = 'student' and target_id = ? and action = 'read'",
                String.class, studentId)).isEqualTo("203.0.113.7");
    }

    private long 등록한다(String realIp) throws Exception {
        MockMultipartFile part = new MockMultipartFile("data", "", "application/json",
                "{\"name\":\"R46감사IP\",\"can_go_alone\":false}".getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/v1/staff/students").file(part)
                        .header("Authorization", 관계자_토큰()).header("X-Real-IP", realIp))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.student_id"));
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }
}
