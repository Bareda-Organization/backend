package src.backend.student.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.11 {@code /staff/students} — STU-01~04 · 07 · 08.
 *
 * <p>이 클래스가 지키는 핵심은 <b>관계자가 입력하지 않는 것 둘</b>(A-10 · 2026-08-24 확정)이다 —
 * 보호자 연락처는 {@code guardian_student} → {@code guardian} → {@code account.phone} 조회이고,
 * 승하차 주소는 학부모 소유라 등록·수정 요청에 받는 자리가 부재하다. 시드는 {@code guardian.phone}
 * 과 {@code account.phone} 이 같은 값이라, 어느 쪽을 읽는 구현인지는 <b>한쪽만 바꿔 본 뒤</b> 응답이
 * 따라오는가로만 갈린다.
 *
 * <p>대조군인 DB 상태는 {@link JdbcTemplate} 으로 직접 읽는다 — 검사 대상인 조회 API 로 대조군을
 * 만들면 그 API 가 잘못돼도 대조군이 함께 틀려 아무것도 못 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffStudentControllerTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    /** 시드 학생 S1(학원 A) — 보호자 {@link SeedFixtures#GUARDIAN_SIBLINGS_ID} 가 연결돼 있다. */
    private static final long SEED_SIBLING_1 = Long.parseLong(SeedFixtures.STUDENT_SIBLING_1_ID);

    /** S1 의 보호자 계정({@code parentA1}) — 이 계정의 {@code phone} 이 목록의 보호자 연락처가 된다. */
    private static final String GUARDIAN_ACCOUNT_LOGIN_ID = SeedFixtures.PARENT_A1_LOGIN_ID;

    private static final String BASE = "/api/v1/staff/students";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 변경 감지가 만든 UPDATE 는 커밋 시점에야 나간다 — 롤백되는 테스트에서 {@link JdbcTemplate} 로
     * 행을 읽어 보려면 그 전에 {@code flush()} 로 밀어내야 한다. 밀지 않으면 구현이 옳아도 실패한다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * 소속 학원은 토큰이 정한다(§1.5) — 요청 본문에 학원을 지정할 자리가 부재하다.
     *
     * <p>DB 를 직접 읽어 확인한다. 등록 응답만 보면 요청을 그대로 되돌려주는 구현과 구별되지 않는다.
     */
    @Test
    void 학생을_등록하면_관계자의_학원_소속으로_저장된다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1소속확인", null));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT academy_id FROM student WHERE id = ?", Long.class, studentId))
                .as("등록한 관계자의 학원으로 저장돼야 한다")
                .isEqualTo(ACADEMY_A);
    }

    /**
     * 주소·보호자 연락처는 관계자 입력 대상 밖이다(A-10 · ERD {@code student} 컬럼 부재).
     *
     * <p>본문에 실어 보내도 <b>받는 자리가 없어야</b> 한다 — 400 으로 거부하는 것이 아니라 그냥
     * 저장되지 않는 것이 사양이다. 상세 응답의 {@code guardian_phone} 이 보낸 값이 아니라
     * {@code null} 인 것으로 "요청값이 아니라 조회값" 임을 고정한다.
     */
    @Test
    void 학생_등록_요청에_주소_필드를_넣어도_저장되지_않는다() throws Exception {
        String body = """
                {"name":"P5T1주소무시","can_go_alone":false,
                 "address":"서울시 강남구 테헤란로 1","guardian_phone":"010-9999-9999"}""";

        long studentId = 등록한다(body);

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").doesNotExist())
                .andExpect(jsonPath("$.data.guardian_phone").value((Object) null));
    }

    /** 보호자 연락처는 {@code student} 가 아니라 연결된 보호자 계정에서 온다(A-10). */
    @Test
    void 보호자가_연결된_학생의_목록_응답에_보호자_계정의_전화번호가_실린다() throws Exception {
        String phone = 보호자_계정_연락처();

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .param("q", 시드_학생_이름(SEED_SIBLING_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").value((int) SEED_SIBLING_1))
                .andExpect(jsonPath("$.data.items[0].guardian_phone").value(phone));
    }

    /**
     * 번호를 바꾸는 것은 <b>계정</b>이다 — {@code guardian.phone} 은 건드리지 않는다.
     *
     * <p>시드는 두 값이 같아서, 이 테스트만이 "어느 쪽을 읽는가" 를 가른다. {@code guardian.phone} 을
     * 읽는 구현이면 옛 값이 그대로 나오고, {@code student} 에 복제한 구현이면 아예 null 이 나온다.
     */
    @Test
    void 보호자가_번호를_바꾸면_학생_목록의_보호자_연락처가_함께_바뀐다() throws Exception {
        String changed = "010-1000-7777";
        jdbcTemplate.update("UPDATE account SET phone = ? WHERE login_id = ?",
                changed, GUARDIAN_ACCOUNT_LOGIN_ID);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone FROM guardian WHERE account_id = (SELECT id FROM account WHERE login_id = ?)",
                String.class, GUARDIAN_ACCOUNT_LOGIN_ID))
                .as("guardian.phone 은 그대로여야 이 테스트가 account.phone 을 읽는지 가른다")
                .isNotEqualTo(changed);

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .param("q", 시드_학생_이름(SEED_SIBLING_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].guardian_phone").value(changed));
    }

    /**
     * 계정 미연결 학생은 연락처가 비어 있는 것이 정상이고, 응답이 그 상태를 그대로 드러낸다(A-10).
     *
     * <p>{@code bus_no}·{@code stop_name} 도 함께 본다 — 노선이 없는 이 Phase 에서 {@code null} 인
     * 것이 계약이라, 필드를 지우면 Phase 6 이 계약을 다시 바꾼다.
     */
    @Test
    void 계정이_연결되지_않은_학생의_보호자_연락처는_null_이다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1미연결학생", null));

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "P5T1미연결학생"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").value((int) studentId))
                .andExpect(jsonPath("$.data.items[0].guardian_phone").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].bus_no").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].stop_name").value((Object) null));
    }

    /**
     * 남의 학원 학생은 <b>없는 것</b>이다 — {@code 403} 이 아니라 {@code 404} 여야 존재 여부가 새지 않는다.
     *
     * <p>같은 식별자를 소유 학원 관계자가 부르면 200 인 것을 함께 본다. 그것이 없으면 "학생 자체가
     * 없어서 404" 인 구현과 구별되지 않는다.
     */
    @Test
    void 다른_학원의_학생을_상세_조회하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        long academyBStudentId = 학원_B_시드_학생();

        mockMvc.perform(get(BASE + "/" + academyBStudentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        mockMvc.perform(get(BASE + "/" + academyBStudentId).header("Authorization", 관계자_토큰(ACADEMY_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value((int) academyBStudentId));
    }

    /**
     * 목록에서 조건 하나가 빠져도 단건 조회는 여전히 404 라, 목록을 따로 본다(ARCHITECTURE §6.1).
     *
     * <p>학원 B 관계자에게는 같은 학생이 보이는 것까지 확인한다 — 안 보면 "아무도 못 보는" 구현이 통과한다.
     */
    @Test
    void 다른_학원의_학생은_목록에_등장하지_않는다() throws Exception {
        long academyBStudentId = 학원_B_시드_학생();

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == %d)]".formatted(academyBStudentId))
                        .isEmpty());

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_B)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == %d)]".formatted(academyBStudentId))
                        .isNotEmpty());
    }

    /** 퇴원은 물리 삭제가 아니라 {@code deleted_at} 을 채우는 soft delete 다(STU-04). */
    @Test
    void 퇴원_처리하면_deleted_at_이_채워지고_목록에서_빠진다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1퇴원대상", null));

        mockMvc.perform(delete(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value((int) studentId))
                .andExpect(jsonPath("$.data.deleted_at").isNotEmpty());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM student WHERE id = ?", Integer.class, studentId))
                .as("물리 삭제 경로를 만들면 안 된다 — 행은 남는다")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM student WHERE id = ?", Timestamp.class, studentId))
                .as("deleted_at 이 채워져야 한다")
                .isNotNull();

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "P5T1퇴원대상"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total_count").value(0));
    }

    /** 퇴원생은 상세 조회에서도 빠진다 — 목록만 거르면 식별자를 아는 사람에게는 계속 보인다. */
    @Test
    void 퇴원한_학생을_상세_조회하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1퇴원상세", null));

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk());

        mockMvc.perform(delete(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    /**
     * 특이사항(STU-07)과 혼자 귀가 가능 여부(STU-08)는 별도 엔드포인트가 아니라 등록·수정의 필드다.
     *
     * <p>등록과 수정 <b>양쪽</b>을 본다 — 한쪽만 보면 다른 쪽이 값을 흘려도 통과한다. 수정 뒤에는
     * 다시 조회해 확인한다. 수정 응답이 요청 본문을 되돌려주는 구현이면 응답만으로는 판정할 수단이 부재하다.
     */
    @Test
    void 특이사항과_혼자_귀가_가능_여부가_등록과_수정에서_저장된다() throws Exception {
        long studentId = 등록한다("""
                {"name":"P5T1특이사항","can_go_alone":true,"note":"땅콩 알레르기"}""");

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(jsonPath("$.data.note").value("땅콩 알레르기"))
                .andExpect(jsonPath("$.data.can_go_alone").value(true));

        mockMvc.perform(patch(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"보호자 동행 필요","can_go_alone":false}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(jsonPath("$.data.note").value("보호자 동행 필요"))
                .andExpect(jsonPath("$.data.can_go_alone").value(false));
    }

    /**
     * 검색어는 결과를 <b>좁혀야</b> 한다 — 조건을 걸지 않은 구현은 두 학생을 모두 돌려준다.
     *
     * <p>기대값을 "1건" 이 아니라 "찾는 학생은 있고 다른 학생은 없다" 로 적는다. 개수만 보면 학원 A 의
     * 시드 학생 수가 바뀔 때 검색과 무관하게 깨진다.
     */
    @Test
    void 검색어로_이름_일부를_주면_해당_학생만_반환한다() throws Exception {
        long 찾을_학생 = 등록한다(등록_본문("P5T1검색가나다", null));
        long 안_찾을_학생 = 등록한다(등록_본문("P5T1검색라마바", null));

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "가나다"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == %d)]".formatted(찾을_학생)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == %d)]".formatted(안_찾을_학생)).isEmpty());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 관계자_토큰(Long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(1L, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String 등록_본문(String name, String note) {
        return "{\"name\":\"%s\",\"can_go_alone\":false%s}"
                .formatted(name, note == null ? "" : ",\"note\":\"" + note + "\"");
    }

    private long 등록한다(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.student_id")).longValue();
    }

    private String 보호자_계정_연락처() {
        return jdbcTemplate.queryForObject(
                "SELECT phone FROM account WHERE login_id = ?", String.class, GUARDIAN_ACCOUNT_LOGIN_ID);
    }

    private String 시드_학생_이름(long studentId) {
        return jdbcTemplate.queryForObject("SELECT name FROM student WHERE id = ?", String.class, studentId);
    }

    private long 학원_B_시드_학생() {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM student WHERE academy_id = ? AND deleted_at IS NULL", Long.class, ACADEMY_B);
    }
}
