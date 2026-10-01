package src.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.notification.entity.NotificationType;

/**
 * 알림 목록 조회·읽음 처리(§3.12·§3.13, Phase 12 T2 goal 1~6) — {@code type}·{@code unread_only}
 * 필터, 봉투 {@code unread_count}, 읽음 처리 성공·403·404, 14일 보관 경계, {@code popup}·
 * {@code student_id}/{@code student_name} 조립을 각각 검사한다.
 *
 * <p>모든 시각은 <b>주입된 {@link Clock} 에서 상대적으로 계산</b>한다({@code now(clock).minusDays(N)}) —
 * 시드 데이터나 하드코딩된 절대 날짜에 기대지 않는다. 이 클래스는 별도 {@code @TestConfiguration} 을
 * 두지 않고 운영 {@code Clock} 빈({@code ClockConfig} — {@code Clock.system(Asia/Seoul)})을 그대로
 * 쓴다 — {@code StaffReportControllerTest} 의 {@code Clock.fixed(하드코딩 절대 순간)} 대신, 테스트가
 * 필요로 하는 것은 "고정된 하루"가 아니라 "지금으로부터 N 일 전" 이라는 상대값뿐이라 실제 흐르는
 * 시계에 오프셋만 적용하면 충분하다(브리프의 {@code Clock.offset} 선호와 같은 방향).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private NotificationLogFixtures fixtures() {
        return new NotificationLogFixtures(academyRepository, accountRepository, jdbcTemplate);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── goal 1 — type 필터 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal1 — type 필터는 매칭 타입만 남기고 비매칭 타입은 뺀다")
    void type_필터는_매칭만_남긴다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long matching = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.ALIGHTING,
                "하차 안내", "하차했습니다", false, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications").param("type", "boarding")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(matching))
                .andExpect(jsonPath("$.data.items[0].type").value("boarding"));
    }

    // ── R46-FUFEAT ③ — 매니저 알림이 회차를 가리킨다(Ruling 542) ──────────────

    @Test
    @DisplayName("R46-FUFEAT ③ — run_id 가 있는 알림은 목록에 run_id 를 싣고, 없는 알림은 키는 있고 값은 null 이다")
    void run_id_는_회차를_가리키는_알림에만_값이_있다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "기사1", Role.DRIVER);
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role,
                                              run_id, type, title, body, dedup_key)
                VALUES (?, ?, '기사1', 'driver', 7777, 'route_changed', '노선 변경', '노선이 바뀌었습니다', 'r46-run-id-1')
                """, academyId, accountId);
        fixtures.notification(academyId, accountId, "기사1", Role.DRIVER, null, null, NotificationType.RUN_STARTED,
                "운행 시작", "시작", false, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications").param("type", "route_changed")
                .header("Authorization", 토큰(accountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].run_id").value(7777));
        mockMvc.perform(get("/api/v1/notifications").param("type", "run_started")
                .header("Authorization", 토큰(accountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].run_id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0]", org.hamcrest.Matchers.hasKey("run_id")));
    }

    // ── goal 1 — unread_only 필터 ─────────────────────────────────────────

    @Test
    @DisplayName("goal1 — unread_only=true 는 미읽음만 남기고 이미 읽은 것은 뺀다")
    void unread_only_필터는_미읽음만_남긴다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long unread = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.ALIGHTING,
                "하차 안내", "하차했습니다", false, now(), now(), now());

        mockMvc.perform(get("/api/v1/notifications").param("unread_only", "true")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(unread));
    }

    // ── goal 1 — 페이징 ───────────────────────────────────────────────────

    @Test
    @DisplayName("goal1 — size 로 페이지를 나누면 has_next 가 다음 페이지 존재를 알린다")
    void 페이징은_size로_나누고_has_next를_알린다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        for (int i = 0; i < 3; i++) {
            fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                    NotificationType.BOARDING, "승차 안내" + i, "승차했습니다", false, now().minusMinutes(i), now(), null);
        }

        mockMvc.perform(get("/api/v1/notifications").param("size", "2")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total_count").value(3))
                .andExpect(jsonPath("$.data.has_next").value(true));

        mockMvc.perform(get("/api/v1/notifications").param("size", "2").param("page", "1")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.has_next").value(false));
    }

    // ── goal 2 — unread_count ────────────────────────────────────────────

    @Test
    @DisplayName("goal2 — 실제 읽음 처리 엔드포인트를 거치면 unread_count 가 1 줄어든다")
    void 읽음_처리_후_unread_count가_감소한다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long targetId = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.ALIGHTING,
                "하차 안내", "하차했습니다", false, now(), now(), null);
        String token = 토큰(accountId, academyId, Role.PARENT);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(2));

        mockMvc.perform(patch("/api/v1/notifications/" + targetId + "/read").header("Authorization", token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1));
    }

    // ── goal 3 — 읽음 처리 성공·403·404 ──────────────────────────────────

    @Test
    @DisplayName("goal3 — 본인 알림 읽음 처리는 204 이고 read_at 이 남는다")
    void 본인_알림_읽음_처리는_204다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long targetId = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);
        String token = 토큰(accountId, academyId, Role.PARENT);

        mockMvc.perform(patch("/api/v1/notifications/" + targetId + "/read").header("Authorization", token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].read_at").exists());
    }

    @Test
    @DisplayName("goal3 급소 — 다른 계정 소유 알림을 읽음 처리하면 403 FORBIDDEN 이다")
    void 타_계정_알림_읽음_처리는_403이다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long ownerAccountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long otherAccountId = fixtures.account(academyId, "보호자2", Role.PARENT);
        long targetId = fixtures.notification(academyId, ownerAccountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);

        mockMvc.perform(patch("/api/v1/notifications/" + targetId + "/read")
                .header("Authorization", 토큰(otherAccountId, academyId, Role.PARENT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("goal3 급소 — 존재하지 않는 id 읽음 처리는 404 NOTIFICATION_NOT_FOUND 다")
    void 존재하지_않는_알림_읽음_처리는_404다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);

        mockMvc.perform(patch("/api/v1/notifications/999999999/read")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
    }

    // ── goal 3 — 중요 통지의 acked (NTF-10, USER_FLOWS §10.2 규칙4·5) ────────
    // acked 는 응답 DTO 에 없다(§3.12 표에 없음, T3 의 관계자 알림 로그가 그대로 읽는 내부 필드다) —
    // 그래서 DB 를 직접 조회해 검사한다.

    @Test
    @DisplayName("goal3 — 지연 알림을 읽음 처리하면 acked·acked_at 도 함께 남는다")
    void 중요_통지_읽음_처리는_acked를_남긴다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long targetId = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.DELAY, "지연 안내", "출발이 지연됩니다", false, now(), now(), null);

        mockMvc.perform(patch("/api/v1/notifications/" + targetId + "/read")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isNoContent());

        // PATCH 가 커밋한 변경은 같은 트랜잭션의 영속성 컨텍스트에만 있어, flush 없이 raw JDBC 로
        // 읽으면 반영 전 값을 본다(같은 트랜잭션·같은 커넥션이라도 Hibernate 버퍼는 자동 동기화되지
        // 않는다) — 실제로 flush 를 빼먹고 돌렸더니 이 단언이 거짓으로 실패했다.
        entityManager.flush();
        Boolean acked = jdbcTemplate.queryForObject("SELECT acked FROM notification_log WHERE id = ?", Boolean.class,
                targetId);
        assertThat(acked).isTrue();
    }

    @Test
    @DisplayName("goal3 — 중요하지 않은 통지를 읽음 처리해도 acked 는 그대로 false 다")
    void 중요하지_않은_통지_읽음_처리는_acked를_남기지_않는다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long targetId = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "승차 안내", "승차했습니다", false, now(), now(), null);

        mockMvc.perform(patch("/api/v1/notifications/" + targetId + "/read")
                .header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isNoContent());

        entityManager.flush();
        Boolean acked = jdbcTemplate.queryForObject("SELECT acked FROM notification_log WHERE id = ?", Boolean.class,
                targetId);
        assertThat(acked).isFalse();
    }

    // ── goal 4 — 보관 14일 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal4 — 13일 전 알림은 보이고 15일 전 알림은 목록에서 빠진다")
    void 보관_14일_경계는_13일_전은_보이고_15일_전은_뺀다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long within = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "13일 전", "승차했습니다", false, now().minusDays(13), now().minusDays(13), null);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.BOARDING,
                "15일 전", "승차했습니다", false, now().minusDays(15), now().minusDays(15), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(within));
    }

    // ── goal 5 — popup ───────────────────────────────────────────────────

    @Test
    @DisplayName("goal5 — popup 필드는 심은 값을 그대로 반영한다")
    void popup_필드는_심은_값을_반영한다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long popupOn = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null,
                NotificationType.EMERGENCY, "비상", "비상 상황입니다", true, now(), now(), null);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.BOARDING,
                "승차 안내", "승차했습니다", false, now().minusMinutes(1), now(), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].notification_id").value(popupOn))
                .andExpect(jsonPath("$.data.items[0].popup").value(true))
                .andExpect(jsonPath("$.data.items[1].popup").value(false));
    }

    // ── goal 6 — student_id · student_name 조립 ──────────────────────────

    @Test
    @DisplayName("goal6 — 학생 관련 알림은 student_id·student_name 이 그대로 실린다")
    void 학생_관련_알림은_student_id_student_name이_실린다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long withStudent = fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, 42L, "홍길동",
                NotificationType.BOARDING, "승차 안내", "홍길동 학생이 승차했습니다", false, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].notification_id").value(withStudent))
                .andExpect(jsonPath("$.data.items[0].student_id").value(42))
                .andExpect(jsonPath("$.data.items[0].student_name").value("홍길동"));
    }

    @Test
    @DisplayName("goal6 — 학생과 무관한 알림(예외 보고 등)은 student_id·student_name 이 없다")
    void 학생과_무관한_알림은_student_필드가_없다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "관계자1", Role.STAFF);
        fixtures.notification(academyId, accountId, "관계자1", Role.STAFF, null, null,
                NotificationType.EXCEPTION_REPORTED, "예외 보고 접수", "도로 통제 상황이 접수됐습니다", false, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", 토큰(accountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].student_name").doesNotExist());
    }

    // ── 학원 격리 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("학원 격리 — 목록은 이 계정 소유 알림만 돌려주고 다른 계정 알림은 새지 않는다")
    void 목록은_다른_계정_알림을_섞지_않는다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long myAccountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        long otherAccountId = fixtures.account(academyId, "보호자2", Role.PARENT);
        long matching = fixtures.notification(academyId, myAccountId, "보호자1", Role.PARENT, null, null,
                NotificationType.BOARDING, "내 알림", "승차했습니다", false, now(), now(), null);
        fixtures.notification(academyId, otherAccountId, "보호자2", Role.PARENT, null, null,
                NotificationType.BOARDING, "남의 알림", "승차했습니다", false, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", 토큰(myAccountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(matching));
    }

    // ── §3.12 sent_at — 발송 시각 없는 행은 created_at 으로 대신한다 (C-null 대상 1) ──

    /**
     * API_SPEC §3.12 는 {@code sent_at} 을 ●(항상 값 있음)로 적었는데, 발송 기록이 없는 행({@code
     * push_state != sent})은 실제로는 {@code sent_at} 컬럼이 비어 있다. §5.17 {@code
     * StaffNotificationItemResponse}(SQL {@code COALESCE(sent_at, created_at)})와 같은 대체를
     * {@link src.backend.notification.dto.NotificationItemResponse#of} 에도 적용했는지 이 시험이
     * 고정한다 — 이 대체가 없으면 {@code sent_at} 이 그대로 {@code null} 로 나가 ● 약속이 깨진다.
     */
    @Test
    @DisplayName("C-null — sent_at 이 비어 있으면 created_at 을 대신 실어 ● 약속을 지킨다")
    void 발송_시각_없는_알림은_sent_at_에_생성_시각을_대신_싣는다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long accountId = fixtures.account(academyId, "보호자1", Role.PARENT);
        // timestamptz 컬럼은 마이크로초까지만 담는다 — 입력을 마이크로초로 미리 잘라 두면 DB 왕복에서
        // 정보 손실이 전혀 없어 아래 비교를 오차 없이 정확히 맞출 수 있다(§Goal3 판정, 원래의
        // `within(1, SECONDS)` 는 이 손실이 없는데도 1초라는 헐거운 창을 둬 검증력을 스스로 버리고
        // 있었다).
        OffsetDateTime createdAt = now().minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
        fixtures.notification(academyId, accountId, "보호자1", Role.PARENT, null, null, NotificationType.BOARDING,
                "승차 안내", "승차했습니다", false, createdAt, null, null);

        MvcResult result = mockMvc
                .perform(get("/api/v1/notifications").header("Authorization", 토큰(accountId, academyId, Role.PARENT)))
                .andExpect(status().isOk())
                .andReturn();

        String sentAt = JsonPath.read(result.getResponse().getContentAsString(), "$.data.items[0].sent_at");
        assertThat(sentAt).as("§3.12 sent_at 은 ● 라 null 이 나가면 안 된다").isNotNull();
        // 서버 응답은 UTC(Z) 오프셋, createdAt 은 Asia/Seoul(+09:00) 오프셋이라 OffsetDateTime#equals
        // 는 오프셋이 달라 항상 실패한다 — Instant 로 바꿔 같은 순간인지를 비교한다.
        assertThat(OffsetDateTime.parse(sentAt).toInstant()).as("발송 기록이 없으면 created_at 을 대신 싣는다")
                .isEqualTo(createdAt.toInstant());
    }

    // ── 호출 도우미 ──────────────────────────────────────────────────────

    /**
     * BR-072 — 메인 관리자는 소속 학원이 없어({@code academyId=null}) 학원 조건으로 좁히면 비상 알림(사고 학원
     * 앞으로 적재)이 목록·미읽음 배지에 항상 0건이다. 전역 계정은 수신자로만 좁힌다.
     */
    @Test
    @DisplayName("BR-072 — 메인 관리자 앞으로 적재한 알림이 목록과 미읽음 배지에 나온다")
    void 메인_관리자_앞_알림이_목록에_나온다() throws Exception {
        NotificationLogFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long adminAccountId = fixtures.account(academyId, "메인관리자", Role.SYSTEM_ADMIN);
        long emergencyId = fixtures.notification(academyId, adminAccountId, "메인관리자", Role.SYSTEM_ADMIN, null, null,
                NotificationType.EMERGENCY, "비상", "비상 발생", true, now(), now(), null);

        mockMvc.perform(get("/api/v1/notifications")
                        .header("Authorization", "Bearer " + tokenProvider.createAccessToken(adminAccountId, null,
                                Role.SYSTEM_ADMIN, AccountStatus.ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value(emergencyId));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
