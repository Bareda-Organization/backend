package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 자녀 연결의 <b>동시성</b> — 같은 보호자가 같은 자녀에 대해 코드 2건을 겹쳐 입력하는 상황
 * (Ruling 164 의 요구가 Ruling 173 으로 이 자리에도 걸렸다).
 *
 * <p>연결 요청을 여러 번 보내면 살아 있는 코드가 둘 이상 생긴다. 두 창에서 각각 코드를 넣으면
 * 두 트랜잭션은 서로의 미커밋 INSERT 를 보지 못해 <b>둘 다</b> "아직 연결 안 됨" 을 읽고 지나가며,
 * 그 뒤 {@code uk_guardian_student} 가 하나를 거부한다. 그 거부를 {@code 409} 로 옮기지 않으면
 * 사용자에게 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 연결됐다" 가 구별되지 않는다.
 *
 * <p><b>어느 층이 잡는지를 타이밍에 맡기지 않는다.</b> 먼저 들어간 트랜잭션은 상대가 실제로
 * {@code INSERT} 에서 DB 잠금을 기다리는 것을 확인한 뒤에야 커밋한다. 신호(latch)만으로 순서를 맞추면
 * 상대의 선검사가 <b>먼저 들어간 쪽의 커밋 이후</b>에 도는 경우가 생겨, 그때는 선검사가 잡아
 * <b>제약 위반 번역 경로가 한 번도 실행되지 않는다</b> — 그 상태에서는 번역을 통째로 지워도 이
 * 테스트가 초록이다({@code BusRegistrationConcurrencyTest} 가 같은 함정을 먼저 밟았다).
 *
 * <p>⚠ <b>BR-024 이후 같은 보호자의 입력은 시도 창 갱신({@code guardian} 행 잠금)에서 줄을 선다</b> — 뒤
 * 요청은 INSERT 가 아니라 그 잠금에서 기다리다 앞 요청의 커밋을 본 뒤 선검사에서 {@code 409} 로 걸린다.
 * 그래서 첫 시험은 이제 제약 위반 번역 경로가 아니라 선검사 경로를 지난다. 번역은 두 번째 방어선으로 남는다.
 *
 * <p>{@code @Transactional} 이 부재한 것이 요점이다 — 테스트가 트랜잭션을 하나 열고 있으면 두 스레드가
 * 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다. 대신 만든 행을
 * {@link #뒷정리한다()} 가 직접 지운다.
 */
@SpringBootTest
class ChildLinkConcurrencyTest {

    /** 형제 S1·S2 의 보호자({@code parentA1}) — {@code studentA4} 와는 아직 연결돼 있지 않다. */
    private static final long GUARDIAN_SIBLINGS_ACCOUNT = 5L;

    /** 계정이 붙은 학원 A 학생({@code studentA4}) — 연결 대상이자 코드 발급 주체다. */
    private static final long STUDENT_A4_ACCOUNT = 10L;

    private static final long STUDENT_A4_ID = 4L;

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);

    /** 학원 A 의 셋째 보호자({@code parentA3}) — {@code studentA4} 와 연결돼 있지 않다. */
    private static final long OTHER_GUARDIAN_ACCOUNT = 7L;

    /**
     * 스레드 하나가 상대를 기다리는 상한 — <b>정상 흐름에서는 소진되지 않는다.</b> 여기 걸리면 상대가
     * 죽었거나 DB 잠금이 안 풀린 것이라, 테스트가 매달리는 대신 실패로 드러나야 한다.
     */
    private static final long TIMEOUT_SECONDS = 20;

    /** 상대가 잠금을 기다리는지 물어보는 간격 — 고정 대기가 아니라 상태를 물어 기다리기 위한 값이다. */
    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private ChildLinkCommandService childLinkCommandService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 앞선_실행이_남긴_행을_지운다() {
        뒷정리한다();
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 이 보호자·학생 조합의 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM link_code WHERE student_id = ?", STUDENT_A4_ID);
        jdbcTemplate.update("UPDATE guardian SET link_attempt_count = 0, link_attempt_window_start = NULL "
                + "WHERE account_id IN (?, ?)", GUARDIAN_SIBLINGS_ACCOUNT, OTHER_GUARDIAN_ACCOUNT);
        jdbcTemplate.update("DELETE FROM guardian_student WHERE guardian_id IN (?, ?) AND student_id = ?",
                보호자_식별자(), 다른_보호자_식별자(), STUDENT_A4_ID);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 ALREADY_LINKED} 다.
     *
     * <p>실패한 쪽이 선검사에서 걸렸는지 DB 에서 걸렸는지는 여기서 가리지 않는다 — <b>어느 쪽이든 같은
     * 코드로 나가야 한다</b> 는 것이 이 단언의 내용이다. 세 단언이 각각 다른 사고를 막는다:
     * 성공 1건(둘 다 통과하면 같은 연결이 두 행) · 실패가 {@code ALREADY_LINKED}(500 누출) ·
     * DB 행 1개(응답과 저장 상태가 갈리는 것).
     */
    @Test
    void 같은_자녀를_동시에_연결하면_성공_1건_실패_1건이고_500_이_부재한다() throws Exception {
        String 코드1 = 요청하고_코드를_받는다();
        // 발급은 이전 코드를 만료시켜(BR-215) 같은 학생의 살아 있는 코드가 둘이 될 수 없다 — 이 시험이 겹치려는
        // "코드 2건" 은 두 번째를 직접 심어 만든다.
        String 코드2 = 코드1.equals("000000") ? "000001" : "000000";
        jdbcTemplate.update("INSERT INTO link_code (student_id, code, expires_at, created_at) "
                + "VALUES (?, ?, now() + interval '10 minutes', now())", STUDENT_A4_ID, 코드2);

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 연결한다(보호자(), 코드1, 먼저_들어갔다, true));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 연결한다(보호자(), 코드2, null, false);
            });

            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().filter(Optional::isEmpty).count())
                .as("성공은 정확히 1건 — 2건이면 같은 연결이 두 번 생긴 것이고 0건이면 정상 연결까지 막힌 것이다")
                .isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("실패는 500(INTERNAL_ERROR)이 아니라 409 ALREADY_LINKED 여야 한다 — 여기까지 온 실패는 "
                        + "선검사를 지나 DB 가 거부한 것이라 제약 위반 번역이 유일한 방어다")
                .containsExactly(ErrorCode.ALREADY_LINKED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ?",
                Integer.class, 보호자_식별자(), STUDENT_A4_ID))
                .as("응답과 무관하게 DB 에 남은 행도 1개여야 한다")
                .isEqualTo(1);
    }

    /**
     * 서로 다른 보호자 둘이 <b>같은 코드</b>를 동시에 넣으면 한 명만 연결된다(BR-085).
     *
     * <p>코드는 화면에 떠 있는 자격 증명이라 두 사람이 함께 볼 수 있다. {@code used_at} 을 읽고 판정한 뒤
     * 덮어쓰기만 하면 두 트랜잭션이 모두 "아직 안 쓰임" 을 보고 지나가, 코드 1개로 연결 2건이 생긴다.
     * 먼저 들어간 쪽은 상대가 잠금을 기다리는 것을 확인하고서야 커밋한다 — 상대의 판정이 반드시 커밋
     * 전의 값을 본 뒤에 겹치게 한다.
     */
    @Test
    void 다른_보호자_둘이_같은_코드를_동시에_넣으면_한_명만_연결된다() throws Exception {
        String 코드 = 요청하고_코드를_받는다();

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(
                    () -> 연결한다(보호자(), 코드, 먼저_들어갔다, true));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 연결한다(다른_보호자(), 코드, null, false);
            });
            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("코드 1개로 두 보호자가 모두 연결됐다 — used_at 판정이 조건부 갱신이 아니다")
                .containsExactly(ErrorCode.LINK_CODE_INVALID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE student_id = ? AND guardian_id IN (?, ?)",
                Integer.class, STUDENT_A4_ID, 보호자_식별자(), 다른_보호자_식별자()))
                .isEqualTo(1);
    }

    /**
     * 한 보호자가 틀린 코드를 {@code 5}번 넣으면 그 뒤로는 <b>맞는 코드도</b> 통하지 않는다(BR-024).
     *
     * <p>요청마다 트랜잭션을 따로 커밋한다 — 실패한 요청의 트랜잭션이 롤백되면서 시도 횟수까지 함께
     * 되돌아가는 구현은 한 트랜잭션 안에서 부르는 시험으로는 드러나지 않는다.
     */
    @Test
    void 틀린_코드를_5번_넣은_보호자는_맞는_코드로도_연결되지_않는다() {
        String 코드 = 요청하고_코드를_받는다();
        String 틀린_코드 = 코드.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(연결한다(보호자(), 틀린_코드, null, false)).contains(ErrorCode.LINK_CODE_INVALID);
        }

        assertThat(연결한다(보호자(), 코드, null, false))
                .as("시도 상한이 없으면 6자리를 훑어 같은 학원 남의 자녀를 연결할 수 있다")
                .contains(ErrorCode.LINK_CODE_INVALID);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ?",
                Integer.class, 보호자_식별자(), STUDENT_A4_ID))
                .isEqualTo(0);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * 발급을 커밋해 학부모가 넣을 코드를 얻는다 — 두 번 부르면 앞 코드는 만료된다(BR-215).
     *
     * <p>SQL 로 직접 심지 않고 서비스를 부르는 이유는, 심어 넣은 행이 실제 발급 경로가 만드는 것과
     * 다른 상태일 수 있기 때문이다. 이 시험이 검사하려는 것은 <b>그 경로가 만든 코드 2개</b>가 겹칠 때다.
     */
    private String 요청하고_코드를_받는다() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> childLinkCommandService.issueCode(학생()).code());
    }

    /**
     * 한 트랜잭션 안에서 코드를 입력한다 — {@code POST /me/students/link} 가 할 일과 같다.
     *
     * <p>예외를 {@link BusinessException} 으로만 받는다 — 그 밖의 예외(예: 번역되지 않은
     * {@code DataIntegrityViolationException})는 여기서 삼키지 않고 그대로 올라가 테스트를 실패시킨다.
     * 삼키면 {@code 500} 누출이 "실패 1건" 으로 세어져 이 테스트가 사고를 통과시킨다.
     *
     * @param guardian     코드를 넣는 보호자
     * @param 들어갔음     INSERT 를 보낸 직후 내리는 신호 — 상대가 이때부터 착수한다
     * @param 상대를_기다림 상대가 INSERT 에서 잠금을 기다리는 것을 확인하고서야 커밋할지
     * @return 성공이면 빈 값, 거부면 그 에러 코드(빈 결과는 컨트롤러와 같이 {@code LINK_CODE_INVALID})
     */
    private Optional<ErrorCode> 연결한다(AuthUser guardian, String code, CountDownLatch 들어갔음,
            boolean 상대를_기다림) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                boolean linked = childLinkCommandService.completeLink(guardian, code).isPresent();
                if (들어갔음 != null) {
                    들어갔음.countDown();
                }
                if (상대를_기다림) {
                    상대가_INSERT_에서_대기할_때까지_커밋을_미룬다();
                }
                return linked ? Optional.<ErrorCode>empty() : Optional.of(ErrorCode.LINK_CODE_INVALID);
            });
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    /**
     * 상대 세션이 {@code guardian_student} INSERT 에서 잠금을 기다리는 상태가 될 때까지 커밋을 미룬다.
     *
     * <p><b>이 대기가 이 테스트의 결정성을 만든다.</b> 상대가 그 상태에 있다는 것은 상대의 선검사가
     * 이미 끝났고(내 행이 미커밋이라 보이지 않았고) 이제 DB 만 남았다는 뜻이다 — 그래서 내가 커밋하는
     * 순간 상대는 <b>반드시</b> 제약 위반을 받는다. 상한에 걸리면 그대로 커밋하고, 그 경우 뒤의 단언이
     * 실패로 드러낸다.
     */
    private void 상대가_INSERT_에서_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시된다(PostgreSQL 16 · stats_fetch_consistency
            // 기본값 cache) — 이 트랜잭션이 처음 읽은 스냅숏이 끝까지 재사용되므로, 상대가 대기에
            // 들어가기 전에 첫 조회가 나가면 그 뒤로는 몇 번을 물어도 0 이 돌아온다. 그러면 이 루프가
            // 상한을 다 쓰고 바깥 Future.get 이 그보다 먼저 만료해 TimeoutException 만 남는다
            // (F5 S3 실측 — 상대는 44초 내내 Lock/transactionid 로 대기 중이었는데 0 이 보였다)
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                    Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long 보호자_식별자() {
        return jdbcTemplate.queryForObject("SELECT id FROM guardian WHERE account_id = ?", Long.class,
                GUARDIAN_SIBLINGS_ACCOUNT);
    }

    /** {@code studentA4} 와 연결되지 않은 학원 A 의 다른 보호자({@code parentA3}, 계정 7). */
    private long 다른_보호자_식별자() {
        return jdbcTemplate.queryForObject("SELECT id FROM guardian WHERE account_id = ?", Long.class,
                OTHER_GUARDIAN_ACCOUNT);
    }

    private AuthUser 다른_보호자() {
        return new AuthUser(OTHER_GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }

    private AuthUser 보호자() {
        return new AuthUser(GUARDIAN_SIBLINGS_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
    }

    private AuthUser 학생() {
        return new AuthUser(STUDENT_A4_ACCOUNT, ACADEMY_A, Role.STUDENT, AccountStatus.ACTIVE);
    }
}
