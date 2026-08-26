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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

    /** 아래 {@link FixedClockConfig} 가 넣어 준 고정 시계 — 퇴원 시각을 같은 순간으로 대조한다. */
    @Autowired
    private Clock clock;

    /**
     * 시각을 고정한다(횡단 규칙 1) — {@code Clock} 을 주입받는 구현과 시스템 시계를 직접 부르는
     * 구현이 이 설정 아래에서 <b>다른 값</b>을 내놓는다.
     *
     * <p>{@code ClockConfig} 의 빈을 덮어쓰지 않고 {@link Primary} 로 하나 더 둔다 — 같은 이름으로
     * 덮으려면 빈 정의 덮어쓰기를 열어야 하고, 그것을 열면 이 클래스 밖의 사고까지 조용히 통과한다.
     */
    @TestConfiguration
    static class FixedClockConfig {

        /** 값 자체에 의미는 없다 — 고정돼 있다는 사실만이 검사 대상이다. */
        private static final Instant FIXED = Instant.parse("2026-08-26T00:00:00Z");

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

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
                .andExpect(jsonPath("$.data.items[0].student_id").value(String.valueOf(SEED_SIBLING_1)))
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
                .andExpect(jsonPath("$.data.items[0].student_id").value(String.valueOf(studentId)))
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
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(academyBStudentId)));
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
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(academyBStudentId))
                        .isEmpty());

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_B)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(academyBStudentId))
                        .isNotEmpty());
    }

    /**
     * 수정 경로의 학원 격리 — 조회와 <b>같은 저장소 메서드</b>를 쓰는지는 이 단언만이 고정한다.
     *
     * <p>404 만 보고 끝내지 않는다. 학원 조건을 뺀 구현은 남의 학원 학생을 <b>실제로 고친 다음</b>
     * 무엇을 반환할지 따로 정하는데, 응답 코드만 보면 "고쳐 놓고 404 를 반환하는" 형태가 통과한다.
     * 그래서 값이 그대로인지를 DB 에서 직접 읽어 대조한다.
     *
     * <p>{@code flush()} 가 이 단언의 전제다 — 변경 감지가 만든 UPDATE 는 커밋 시점에야 나가므로,
     * 밀어내지 않으면 실제로 고친 구현도 DB 에서는 옛 값으로 보여 단언이 아무것도 검사하지 않는다.
     */
    @Test
    void 다른_학원의_학생을_수정하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        long academyBStudentId = 학원_B_시드_학생();
        String before = 시드_학생_이름(academyBStudentId);

        mockMvc.perform(patch(BASE + "/" + academyBStudentId).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"P5T1남의학원수정","note":"침입","can_go_alone":true}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        entityManager.flush();
        assertThat(시드_학생_이름(academyBStudentId))
                .as("거부했다면 남의 학원 학생의 값은 하나도 바뀌지 않아야 한다")
                .isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT note FROM student WHERE id = ?", String.class, academyBStudentId))
                .as("요청 본문의 특이사항이 남의 학원 학생에 실리면 안 된다")
                .isNotEqualTo("침입");
    }

    /**
     * 퇴원 경로의 학원 격리 — {@code deleted_at} 이 NULL 로 남는 것까지 본다.
     *
     * <p>응답 코드만 보면 <b>퇴원시켜 놓고 404 를 반환하는</b> 구현이 통과한다. 그 형태는 남의 학원
     * 학생이 명단에서 사라지는 사고인데, 사라진 쪽 학원에는 아무 흔적도 남지 않는다.
     */
    @Test
    void 다른_학원의_학생을_퇴원_처리하면_404_STUDENT_NOT_FOUND_이다() throws Exception {
        long academyBStudentId = 학원_B_시드_학생();

        mockMvc.perform(delete(BASE + "/" + academyBStudentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM student WHERE id = ?", Timestamp.class, academyBStudentId))
                .as("거부했다면 남의 학원 학생은 재학 상태로 남아야 한다")
                .isNull();
    }

    /** 퇴원은 물리 삭제가 아니라 {@code deleted_at} 을 채우는 soft delete 다(STU-04). */
    @Test
    void 퇴원_처리하면_deleted_at_이_채워지고_목록에서_빠진다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1퇴원대상", null));

        mockMvc.perform(delete(BASE + "/" + studentId).header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(studentId)))
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

    /**
     * 퇴원 시각은 <b>주입된 {@code Clock} 기준 현재</b>여야 한다(횡단 규칙 1).
     *
     * <p>비-null 확인만으로는 {@code deleted_at} 에 미래 시각을 넣는 구현이 그대로 통과한다 — 실제로
     * 게이트 리뷰가 그 변형을 심어 11개 단언 전부를 지나갔다. 미래 시각이 위험한 이유는 "오늘 명단은
     * 유지, 내일부터 제외"(STU-04)가 <b>이 값과 오늘을 견주어</b> 결정되기 때문이다 — 시각이 앞서
     * 나가면 퇴원생이 며칠 더 명단에 남는다.
     *
     * <p>시스템 시계로 "대략 지금" 을 비교하지 않고 {@link Clock} 을 고정한 채 <b>같은 순간</b>을
     * 단언한다. 허용 구간을 두면 그 폭만큼의 오차는 다시 검사 밖으로 나간다.
     */
    @Test
    void 퇴원_시각은_주입된_시계의_현재_시각이다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1퇴원시각", null));

        MvcResult result = mockMvc.perform(delete(BASE + "/" + studentId)
                        .header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andReturn();

        String deletedAt = JsonPath.read(본문(result), "$.data.deleted_at");
        assertThat(OffsetDateTime.parse(deletedAt))
                .as("고정한 시계의 현재와 같은 순간이어야 한다 — 시스템 시계를 직접 부르면 이 단언이 문다")
                .isEqualTo(OffsetDateTime.now(clock));
    }

    /**
     * 요청한 {@code size} 만큼만 돌려주고 페이지 경계가 맞아야 한다(§1.8).
     *
     * <p>{@code items} 길이만 보면 부족하다 — 요청 크기를 무시하고 100 으로 고정한 구현은 학생이
     * 100명 미만인 테스트에서 <b>모든 단언을 지나간다</b>(게이트 리뷰가 심어 확인). 그래서
     * {@code total_count}(전체 3)와 {@code has_next}(다음 쪽 있음)를 함께 보고, <b>다음 쪽을 실제로
     * 받아</b> 남은 1건과 {@code has_next=false} 까지 확인한다.
     */
    @Test
    void 목록은_요청한_size_만큼만_돌려주고_다음_쪽이_있는지_알려준다() throws Exception {
        등록한다(등록_본문("P5T1페이징학생가", null));
        등록한다(등록_본문("P5T1페이징학생나", null));
        등록한다(등록_본문("P5T1페이징학생다", null));

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .param("q", "P5T1페이징학생").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total_count").value(3))
                .andExpect(jsonPath("$.data.has_next").value(true));

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .param("q", "P5T1페이징학생").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false));
    }

    /**
     * 보호자가 둘일 때 대표로 실리는 것은 <b>먼저 연결된</b> 쪽이다(쿼리의 {@code ORDER BY
     * gs.linkedAt} + {@code putIfAbsent}).
     *
     * <p>시드는 전부 1학생-1보호자라 이 규칙을 무는 단언이 없었다. 픽스처는 <b>나중에 만든 행의
     * 연결 시각을 더 이르게</b> 잡는다 — 그래야 "먼저 연결된 쪽" 과 "먼저 만들어진 행" 이 갈려,
     * 정렬 축을 {@code gs.id} 로 바꾸거나 정렬을 통째로 빼는 변경이 이 단언에 걸린다.
     */
    @Test
    void 보호자가_둘이면_먼저_연결된_쪽의_번호가_대표로_실린다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1보호자둘", null));
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A1_LOGIN_ID), "now()");
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A2_LOGIN_ID), "now() - interval '1 hour'");

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "P5T1보호자둘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].guardian_phone")
                        .value(계정_연락처(SeedFixtures.PARENT_A2_LOGIN_ID)));
    }

    /**
     * 연결 시각이 같으면 <b>먼저 만들어진 연결</b>이 대표다({@code ORDER BY … gs.id}).
     *
     * <p>연결 시각만으로는 순서가 정해지지 않는 경우가 실제로 생긴다 — 두 보호자를 한 화면에서
     * 잇거나 같은 트랜잭션에서 심으면 {@code linked_at} 이 같은 값이 된다. 이때 무엇이 이기는지
     * 적어 두지 않으면 DB 가 정하고, 그 순서는 계약이 아니다.
     */
    @Test
    void 보호자_둘의_연결_시각이_같으면_먼저_만들어진_연결이_대표다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1동시연결", null));
        String sameMoment = "timestamptz '2026-08-26 09:00:00+09'";
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A1_LOGIN_ID), sameMoment);
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A2_LOGIN_ID), sameMoment);

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "P5T1동시연결"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].guardian_phone")
                        .value(계정_연락처(SeedFixtures.PARENT_A1_LOGIN_ID)));
    }

    /**
     * 보호자가 둘이어도 목록이 세는 것은 <b>학생 수</b>다 — 연결 수가 아니다.
     *
     * <p>연락처를 목록 쿼리에 조인해 한 번에 뽑는 구현으로 바꾸면 학생 1명이 행 2개가 되어
     * {@code total_count} 가 2 로, {@code items} 가 중복 2건으로 나온다. 소수 데이터에서는 화면이
     * 그럴듯해 아무도 알아채지 못하고, 페이지를 넘길 때 비로소 학생이 건너뛰어진다.
     */
    @Test
    void 보호자가_둘이어도_목록의_total_count_는_학생_수를_센다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1중복계수", null));
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A1_LOGIN_ID), "now()");
        연결한다(studentId, 보호자_식별자(SeedFixtures.PARENT_A2_LOGIN_ID), "now()");

        mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A)).param("q", "P5T1중복계수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_count").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
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
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(찾을_학생)).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.student_id == '%d')]".formatted(안_찾을_학생)).isEmpty());
    }

    /**
     * {@code student_id} 는 <b>JSON 문자열</b>이다(Ruling 171) — 목록 · 상세 · 퇴원 응답 셋 다.
     *
     * <p><b>{@code .value(String.valueOf(...))} 로는 이것이 고정되지 않는다.</b> MockMvc 의
     * {@code jsonPath(...).value(Object)} 는 실제 값의 타입이 기대값과 다르면 <b>기대 타입으로 다시
     * 읽어</b> 비교하므로, 숫자 {@code 4} 도 {@code "4"} 와 같다고 판정한다. 실제로 DTO 를 숫자로
     * 되돌리는 변형을 심었더니 이 클래스 전체가 통과했다(수정 라운드 1 음성 대조 N7).
     *
     * <p>그래서 값이 아니라 <b>타입</b>을 본다. 세 응답을 함께 보는 이유는 DTO 가 셋이라 하나만
     * 고정하면 나머지 둘이 조용히 숫자로 되돌아가기 때문이다.
     *
     * <p>숫자로 나가면 JavaScript 클라이언트가 2^53 을 넘는 식별자에서 값을 잃고, 그때는 요청이
     * 실패하는 것이 아니라 <b>다른 학생을 가리킨다.</b>
     */
    @Test
    void 학생_응답의_student_id_는_JSON_문자열이다() throws Exception {
        long studentId = 등록한다(등록_본문("P5T1식별자타입", null));

        MvcResult 목록 = mockMvc.perform(get(BASE).header("Authorization", 관계자_토큰(ACADEMY_A))
                        .param("q", "P5T1식별자타입"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((Object) JsonPath.read(본문(목록), "$.data.items[0].student_id"))
                .as("목록 items[].student_id")
                .isInstanceOf(String.class);

        MvcResult 상세 = mockMvc.perform(get(BASE + "/" + studentId)
                        .header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((Object) JsonPath.read(본문(상세), "$.data.student_id"))
                .as("상세 student_id")
                .isInstanceOf(String.class);

        MvcResult 퇴원 = mockMvc.perform(delete(BASE + "/" + studentId)
                        .header("Authorization", 관계자_토큰(ACADEMY_A)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((Object) JsonPath.read(본문(퇴원), "$.data.student_id"))
                .as("퇴원 student_id")
                .isInstanceOf(String.class);
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
        return Long.parseLong(JsonPath.read(본문(result), "$.data.student_id"));
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 보호자_계정_연락처() {
        return 계정_연락처(GUARDIAN_ACCOUNT_LOGIN_ID);
    }

    private String 계정_연락처(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT phone FROM account WHERE login_id = ?", String.class, loginId);
    }

    private long 보호자_식별자(String loginId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM guardian WHERE account_id = (SELECT id FROM account WHERE login_id = ?)",
                Long.class, loginId);
    }

    /**
     * 보호자 ↔ 학생 연결을 SQL 로 심는다 — 연결을 만드는 API 는 이 태스크 범위 밖(P5-T3)이라
     * 재료를 직접 넣는다.
     *
     * <p>{@code linkedAtExpression} 을 문자열로 받는 것은 <b>같은 시각</b>과 <b>1시간 전</b>을
     * SQL 식으로 정해야 하기 때문이다 — 자바에서 만든 시각을 바인딩하면 두 행이 미세하게 달라져
     * 동시 연결 시나리오가 성립하지 않는다. 값은 테스트가 정한 리터럴뿐이라 외부 입력이 부재하다.
     */
    private void 연결한다(long studentId, long guardianId, String linkedAtExpression) {
        jdbcTemplate.update("INSERT INTO guardian_student (guardian_id, student_id, linked_at) VALUES (?, ?, %s)"
                .formatted(linkedAtExpression), guardianId, studentId);
    }

    private String 시드_학생_이름(long studentId) {
        return jdbcTemplate.queryForObject("SELECT name FROM student WHERE id = ?", String.class, studentId);
    }

    private long 학원_B_시드_학생() {
        return jdbcTemplate.queryForObject(
                "SELECT min(id) FROM student WHERE academy_id = ? AND deleted_at IS NULL", Long.class, ACADEMY_B);
    }
}
