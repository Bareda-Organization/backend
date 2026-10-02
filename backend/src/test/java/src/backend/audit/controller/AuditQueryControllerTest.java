package src.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * Phase 14 T1 목표 3·4 음성 대조용 — {@code /admin/audit-logs} 와 {@code /admin/login-history} 가
 * 서버 쪽 {@code category} 하드 필터를 지키는지, {@code academy_id}·{@code account_id} 필터가 미등록
 * 대상에서 404 를 내는지 직접 HTTP 로 확인한다.
 *
 * <p>{@link #login_history_는_data_access_카테고리_행을_반환하지_않는다} 와
 * {@link #audit_logs_는_login_카테고리_행을_반환하지_않는다} 가 negative control #6 을 겨눈다 — 두
 * 서비스가 {@code auditLogRepository.search(category, ...)} 에 넘기는 카테고리를 지우거나 무시하면,
 * {@code account_id} 로 상대 카테고리 전용 계정을 필터링했을 때 그 계정의 행이 새어 나온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditQueryControllerTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;
    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    private Long createAccount(String academyCode, String loginId) {
        Academy academy = academyRepository.save(Academy.register(academyCode, "학원" + academyCode, "서울", null, null));
        return createAccountIn(academy, loginId, "감사조회테스트", Role.PARENT);
    }

    private Long createAccountIn(Academy academy, String loginId, String name, Role role) {
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(RAW_PASSWORD), name, "010-9100-0000", null, role));
        return account.getId();
    }

    @Test
    void login_history_는_data_access_카테고리_행을_반환하지_않는다() throws Exception {
        Long dataAccessOnlyAccountId = createAccount("P14T1AUD05", "p14t1queryda1");
        auditLogRepository.save(AuditLog.forDataAccessRead(null, dataAccessOnlyAccountId, "p14t1queryda1",
                "run_roster", 999L, Map.of("student_ids", List.of("1"), "fields", List.of("note")), null,
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/login-history")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(dataAccessOnlyAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void audit_logs_는_login_카테고리_행을_반환하지_않는다() throws Exception {
        Long loginOnlyAccountId = createAccount("P14T1AUD06", "p14t1queryln1");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"p14t1queryln1\", \"password\": \"%s\"}".formatted(RAW_PASSWORD)));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(loginOnlyAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * F1 결과 ④(R1 ⚠) — 이 클래스 {@code audit_logs_응답은_snake_case_키_6개를_그대로_노출한다} 의
     * 자바독이 "학원 {@code null} 로 두면 NPE 가 난다" 며 일부러 피하던 경로다. F1 이
     * {@code AuditLogQueryService.academyNamesOf} 에 방어({@code HashMap} 반환)를 넣었으니, 그 경로를
     * 실제로 타는 시험이 하나는 있어야 이후 그 방어가 조용히 사라지는 것을 잡는다.
     */
    @Test
    void audit_logs_는_academy_id가_null인_행도_200으로_academy_name_null을_반환한다() throws Exception {
        Long accountId = createAccount("P14T1AUD09", "p14t1nullacd1");
        auditLogRepository.save(AuditLog.forDataAccessRead(null, accountId, "p14t1nullacd1",
                "emergency", 777L, Map.of("student_ids", List.of("1"), "fields", List.of("note")), null,
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].target_type").value("emergency"))
                .andExpect(jsonPath("$.data.items[0].academy_name").value(nullValue()));
    }

    @Test
    void audit_logs_는_academy_id_가_미등록이면_404_ACADEMY_NOT_FOUND_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("academy_id", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
    }

    @Test
    void login_history_는_account_id_가_미등록이면_404_ACCOUNT_NOT_FOUND_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/login-history")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", "999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    /**
     * Phase 14 수정 라운드 1 — §6.13 {@code /admin/audit-logs} 응답 JSON 키 6개를 문자열로 단언한다
     * (review-p14-r1.md 재판정 ③, {@code AuditLogItemResponse.targetType} → {@code targetKind} 개명이
     * 살아남는지 이 테스트가 가른다).
     *
     * <p>{@code academy_id} 는 실제 사용 경로(학생·회차 명단 조회는 전부 학원 범위 안)와 같게 실
     * 학원을 채운다 — {@code null} 로 두면 {@code AuditLogQueryService.academyNamesOf} 가 만드는
     * {@code Map.of()}(빈 불변 맵)에 {@code null} 키로 {@code get} 을 호출해 NPE 가 나는 별개의
     * 결함을 이 테스트가 우연히 건드리게 된다 — 그 결함은 이번 라운드 대상(JSON 키 노출)이 아니라
     * 별도로 보고한다.
     */
    @Test
    void audit_logs_응답은_snake_case_키_6개를_그대로_노출한다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("P14T1AUD07", "감사키확인학원", "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), "p14t1jsonkey1",
                passwordEncoder.encode(RAW_PASSWORD), "감사조회테스트", "010-9100-0001", null, Role.PARENT));
        auditLogRepository.save(AuditLog.forDataAccessRead(academy.getId(), account.getId(), "p14t1jsonkey1",
                "run_roster", 12345L, Map.of("student_ids", List.of("1"), "fields", List.of("note")), null,
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(account.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].actor").value("p14t1jsonkey1"))
                .andExpect(jsonPath("$.data.items[0].action").value("read"))
                .andExpect(jsonPath("$.data.items[0].target_type").value("run_roster"))
                .andExpect(jsonPath("$.data.items[0].target_id").value(12345))
                .andExpect(jsonPath("$.data.items[0].academy_name").value("감사키확인학원"))
                .andExpect(jsonPath("$.data.items[0].occurred_at").exists());
    }

    /**
     * Phase 14 수정 라운드 1 — §6.13 {@code /admin/login-history} 응답 JSON 키 6개를 문자열로
     * 단언한다(review-p14-r1.md 재판정 ③).
     */
    @Test
    void login_history_응답은_snake_case_키_6개를_그대로_노출한다() throws Exception {
        Long accountId = createAccount("P14T1AUD08", "p14t1jsonkey2");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"p14t1jsonkey2\", \"password\": \"%s\"}".formatted(RAW_PASSWORD)));

        mockMvc.perform(get("/api/v1/admin/login-history")
                        .header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].account_id").value(accountId))
                .andExpect(jsonPath("$.data.items[0].login_id").value("p14t1jsonkey2"))
                .andExpect(jsonPath("$.data.items[0].result").value("success"))
                .andExpect(jsonPath("$.data.items[0].ip").exists())
                .andExpect(jsonPath("$.data.items[0].occurred_at").exists())
                .andExpect(jsonPath("$.data.items[0].block_event").value(false));
    }

    /** R46 감사 화면(Ruling 446) — {@code action} 으로 조회·수정·삭제를 가른다. 안 주면 셋 다 나온다. */
    @Test
    void audit_logs_는_action_필터로_조회_수정_삭제를_가른다() throws Exception {
        Long accountId = createAccount("R46AUD01", "r46actionflt1");
        OffsetDateTime now = OffsetDateTime.now();
        auditLogRepository.save(AuditLog.forDataAccessRead(null, accountId, "r46actionflt1", "student", 1L,
                Map.of("student_ids", List.of("1")), null, now));
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.UPDATE, null, accountId, "r46actionflt1",
                "student", 1L, Map.of("fields", List.of("note")), null, now));
        auditLogRepository.save(AuditLog.forDataAccessChange(AuditAction.DELETE, null, accountId, "r46actionflt1",
                "student", 1L, Map.of(), null, now));

        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(3));
        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", 메인관리자_토큰())
                        .param("account_id", String.valueOf(accountId)).param("action", "update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("update"));
    }

    @Test
    void audit_logs_의_action_이_조회_수정_삭제_밖이면_422_다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", 메인관리자_토큰())
                        .param("action", "login_success"))
                .andExpect(status().isUnprocessableContent());
    }

    /** R46 감사 화면(Ruling 447) — 이름·로그인 아이디 일부로 계정을 찾는다. 검색어가 비면 아무도 안 돌려준다. */
    @Test
    void audit_actors_는_이름_또는_로그인_아이디_일부로_계정을_찾는다() throws Exception {
        Long accountId = createAccount("R46AUD02", "r46actorfind1");

        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "R46ACTORFIND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].account_id").value(String.valueOf(accountId)))
                .andExpect(jsonPath("$.data.items[0].login_id").value("r46actorfind1"))
                .andExpect(jsonPath("$.data.items[0].name").value("감사조회테스트"));
        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 화면 목적은 "이름으로 고른다" 다(API_SPEC §6.13) — 검색어가 로그인 아이디에는 없고 <b>이름에만</b> 들어 있어도 찾는다.
     * 대소문자는 구별하지 않는다(이름의 영문 대문자를 소문자로 쳐도 찾는다).
     */
    @Test
    void audit_actors_는_이름에만_들어_있는_검색어로도_계정을_찾는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R339NAME", "학원R339NAME", "서울", null, null));
        Long accountId = createAccountIn(academy, "r339login1", "이름전용Kim", Role.PARENT);

        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "이름전용kim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].account_id").value(String.valueOf(accountId)))
                .andExpect(jsonPath("$.data.items[0].login_id").value("r339login1"));
    }

    /** 응답이 같은 이름을 구별하는 단서(역할·소속 학원)를 싣는다 — 역할은 {@code Role} 값 그대로 소문자, 학원은 이름이다. */
    @Test
    void audit_actors_는_역할_소문자와_소속_학원_이름을_싣는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R339ROLE", "학원R339ROLE", "서울", null, null));
        createAccountIn(academy, "r339role1", "단서시험", Role.ESCORT);

        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "r339role1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("escort"))
                .andExpect(jsonPath("$.data.items[0].academy_name").value("학원R339ROLE"));
    }

    /** 소속 학원이 없는 계정(메인 관리자)은 {@code academy_name} 이 {@code null} 이다 — 학원 이름 조회가 그 계정에서 깨지지 않는다. */
    @Test
    void audit_actors_는_소속_없는_계정의_학원_이름을_null_로_돌려준다() throws Exception {
        String systemAdminLoginId = accountRepository.findById(SYSTEM_ADMIN_ACCOUNT_ID).orElseThrow().getLoginId();

        String body = mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", systemAdminLoginId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 필터는 항상 배열을 돌려준다 — 같은 검색어에 다른 계정도 걸릴 수 있어 메인 관리자 항목만 골라 본다
        Map<String, Object> admin = JsonPath.<List<Map<String, Object>>>read(body,
                "$.data.items[?(@.account_id == '" + SYSTEM_ADMIN_ACCOUNT_ID + "')]").get(0);
        assertThat(admin.get("role")).isEqualTo("system_admin");
        assertThat(admin).as("소속이 없으면 키는 있고 값이 null").containsEntry("academy_name", null);
    }

    /**
     * 검색어의 {@code %}·{@code _} 는 글자 그대로다(BR-376 — 학생·학원·매니저 검색과 같은 {@code LikeEscape} 규칙).
     * 이스케이프하지 않으면 {@code _} 가 "아무 한 글자" 가 되어 밑줄이 없는 계정까지 걸린다.
     */
    @Test
    void audit_actors_는_검색어의_밑줄을_글자_그대로_찾는다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R376LIKE", "학원R376LIKE", "서울", null, null));
        Long underscored = createAccountIn(academy, "r376_like", "밑줄시험", Role.PARENT);
        createAccountIn(academy, "r376xlike", "밑줄없음", Role.PARENT);

        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "r376_like"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].account_id").value(String.valueOf(underscored)));
    }

    /** 최대 20건에서 자른다(API_SPEC §6.13) — 21건이 걸리는 검색어도 20건만 돌려준다. 자르지 않으면 전 계정이 응답으로 나간다. */
    @Test
    void audit_actors_는_21건이_걸리는_검색어에도_20건만_돌려준다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("R339LIMIT", "학원R339LIMIT", "서울", null, null));
        for (int i = 0; i < 21; i++) {
            createAccountIn(academy, "r339limit" + i, "상한시험" + i, Role.PARENT);
        }

        mockMvc.perform(get("/api/v1/admin/audit-actors").header("Authorization", 메인관리자_토큰())
                        .param("q", "r339limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(20));
    }
}
