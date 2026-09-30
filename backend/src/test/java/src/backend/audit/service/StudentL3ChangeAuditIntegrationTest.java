package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.HttpMethod;
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
 * L3 감사는 조회·수정·삭제다(`Ruling 333` · SYS-01 · BR-060) — 상세 조회만 남기던 것을 관계자 학생 목록(보호자
 * 연락처 원본을 싣는 학생마다 {@code read} 1행) · L3 수정({@code update}) · 퇴원({@code delete})으로 넓힌다.
 *
 * <p>목록 감사는 {@code REQUIRES_NEW} 로 커밋돼 롤백 뒤에도 남는다 — 그래서 요청 전 행 id 를 떠 두고 새 행만 센다
 * ({@link StudentDetailAuditIntegrationTest} 와 같은 이유). 수정·퇴원 감사는 변경과 같은 트랜잭션이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentL3ChangeAuditIntegrationTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    private static final long SIBLING_1_ID = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void 학생_목록은_보호자_연락처가_실린_학생마다_read_1행을_남긴다() throws Exception {
        Set<Long> before = 학생_감사_행(AuditAction.READ).stream().map(AuditLog::getId).collect(Collectors.toSet());

        // 같은 행위자가 10분 안에 다시 읽은 학생은 기록하지 않는다(Ruling 445) — 다른 시험이 읽은 학생이 빠지지 않게
        // 이 시험만의 행위자로 읽는다
        MvcResult result = mockMvc.perform(get("/api/v1/staff/students").param("size", "100")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(
                                7_700_000_001L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE)))
                .andExpect(status().isOk())
                .andReturn();

        List<String> withPhone = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.items[?(@.guardian_phone != null)].student_id");
        assertThat(withPhone).as("보호자 연락처가 실린 학생이 없으면 아래 단언이 비교할 것이 없다").isNotEmpty();
        assertThat(학생_감사_행(AuditAction.READ).stream().filter(log -> !before.contains(log.getId()))
                .map(log -> String.valueOf(log.getTargetId())))
                .as("목록이 보호자 연락처 원본을 실어 보냈는데 감사 행이 그 학생 수만큼 남지 않았다")
                .containsExactlyInAnyOrderElementsOf(withPhone);
    }

    @Test
    void L3_필드를_고치면_update_1행에_고친_필드명만_남긴다() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/staff/students/" + SIBLING_1_ID)
                        .file(데이터("{\"note\":\"감사대상 특이사항\"}"))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        List<AuditLog> rows = 대상_행(AuditAction.UPDATE, SIBLING_1_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDetail().get("fields")).isEqualTo(List.of("note"));
        assertThat(rows.get(0).getDetail().toString())
                .as("감사 행에 개인정보 원문을 싣지 않는다(TECH_DECISIONS §13.2)")
                .doesNotContain("감사대상 특이사항");
    }

    @Test
    void L3_가_아닌_필드만_고치면_update_행이_없다() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v1/staff/students/" + SIBLING_1_ID)
                        .file(데이터("{\"class_name\":\"C반\"}"))
                        .header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        assertThat(대상_행(AuditAction.UPDATE, SIBLING_1_ID)).isEmpty();
    }

    @Test
    void 퇴원하면_delete_1행을_남긴다() throws Exception {
        mockMvc.perform(delete("/api/v1/staff/students/" + SIBLING_1_ID).header("Authorization", 관계자_토큰()))
                .andExpect(status().isOk());

        assertThat(대상_행(AuditAction.DELETE, SIBLING_1_ID)).hasSize(1);
    }

    private List<AuditLog> 학생_감사_행(AuditAction action) {
        return auditLogRepository.findAll().stream()
                .filter(log -> log.getCategory() == AuditCategory.DATA_ACCESS && log.getAction() == action
                        && "student".equals(log.getTargetType()))
                .toList();
    }

    private List<AuditLog> 대상_행(AuditAction action, long studentId) {
        return 학생_감사_행(action).stream().filter(log -> log.getTargetId().equals(studentId)).toList();
    }

    private MockMultipartFile 데이터(String json) {
        return new MockMultipartFile("data", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private String 관계자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }
}
