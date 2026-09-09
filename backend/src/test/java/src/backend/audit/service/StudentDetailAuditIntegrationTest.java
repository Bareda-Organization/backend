package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * Phase 14 수정 라운드 1 — {@link src.backend.student.query.StudentQueryService#detail} 의 감사
 * 호출에 전담 단언을 둔다({@code review-p14-r1.md} 🔴 1건 — R1 이 no-op 변형을 심어 살아남음을 확인한
 * 자리).
 *
 * <p>시드의 기존 학생(예: {@link SeedFixtures#STUDENT_SIBLING_1_ID})을 재사용하지 않는다 —
 * {@code AuditRecorder} 가 {@code REQUIRES_NEW} 로 감사 행을 커밋하므로, 이 테스트의 트랜잭션이
 * 끝에 롤백돼도 감사 행은 영구히 남는다. 공유 테스트 DB({@code sb_p14_t1})에서 같은 시드 학생 id 를
 * 여러 라운드에 걸쳐 재사용하면 이전 실행이 남긴 행이 누적돼 {@code hasSize(1)} 이 실행 순서에 따라
 * 거짓 실패한다(실측 — 처음엔 size 2 로 실패). 그래서 이 테스트 안에서 학생을 새로 등록해, 실제 DB
 * 시퀀스가 발급하는 유일한 id 로 대상을 좁힌다.
 *
 * <p>등록 자체도 {@code target_id} 가 같은 감사 행을 하나 남긴다 — {@code StaffStudentController.register}
 * 가 응답을 조립할 때 {@code studentQueryService.detail(...)} 을 그대로 호출하기 때문이다(§1.9 "쓰기
 * 후 자원 상태를 그대로 반환"). 그래서 {@code target_id} 만으로 걸러 {@code hasSize(1)} 을 단언하면
 * 등록이 남긴 행과 이 테스트가 실제로 검증하려는 GET 호출의 행을 구별하지 못한다(실측 — size 2 로
 * 실패). 등록 직후의 행 id 집합을 먼저 떠 두고, GET 이후 그 집합에 없는 새 행만 걸러 이 GET 호출이
 * 남긴 행 정확히 1건인지를 가른다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentDetailAuditIntegrationTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void 관계자_학생_상세_조회는_감사_로그_1건을_남기고_student_ids_를_담는다() throws Exception {
        long studentId = 등록한다("{\"name\":\"P14T1감사대상\",\"can_go_alone\":false}");

        Set<Long> idsBeforeGet = 대상_감사_행(studentId).stream().map(AuditLog::getId).collect(Collectors.toSet());

        mockMvc.perform(get("/api/v1/staff/students/" + studentId)
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        List<AuditLog> newRows = 대상_감사_행(studentId).stream()
                .filter(log -> !idsBeforeGet.contains(log.getId()))
                .toList();
        assertThat(newRows)
                .as("GET 호출 자체가 새로 남긴 행 — 등록이 이미 남긴 행은 idsBeforeGet 으로 제외했다")
                .hasSize(1);
        AuditLog row = newRows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.READ);
        assertThat(row.getAcademyId()).isEqualTo(ACADEMY_A);
        @SuppressWarnings("unchecked")
        List<String> studentIds = (List<String>) row.getDetail().get("student_ids");
        assertThat(studentIds).containsExactly(String.valueOf(studentId));
    }

    private List<AuditLog> 대상_감사_행(long studentId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> log.getCategory() == AuditCategory.DATA_ACCESS
                        && "student".equals(log.getTargetType()) && log.getTargetId().equals(studentId))
                .toList();
    }

    private long 등록한다(String body) throws Exception {
        MockMultipartFile part = new MockMultipartFile("data", "", "application/json",
                body.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/v1/staff/students").file(part)
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.student_id"));
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }
}
