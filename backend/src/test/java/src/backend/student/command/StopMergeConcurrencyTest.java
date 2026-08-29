package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 승하차지 근접 병합(STU-05)의 <b>동시성</b> — 같은 주소를 동시에 등록해도 승하차지가 1개만 생기는가
 * (Phase 6 목표 8 · Ruling 179).
 *
 * <p>병합은 "조회 → 판정 → 생성" 세 걸음이라 잠금이 없으면 두 트랜잭션이 <b>둘 다</b> 후보 부재로
 * 판정하고 각자 만든다. 그 결과 같은 자리에 승하차지가 둘 생기고, 노선 계산의 입력이 갈린다.
 * 좌표 UNIQUE 로는 막을 수 없다 — 병합 규칙이 좌표 동일성이 아니라 반경이라 1m 떨어진 두 좌표는
 * 제약을 지나면서 병합 대상이다.
 *
 * <p><b>{@code @Transactional} 이 부재한 것이 요점이다.</b> 테스트가 트랜잭션을 하나 열고 있으면 두
 * 스레드가 그것을 공유해 "서로의 미커밋 INSERT 를 보지 못하는" 상황 자체가 만들어지지 않고, 그러면
 * 이 클래스의 모든 단언이 아무것도 검사하지 않는다. 대신 만든 행을 {@link #뒷정리한다()} 가 직접 지운다.
 *
 * <p>동시 요청을 4건으로 두는 것은 {@code build.gradle} 이 테스트 커넥션 풀 상한을 6으로 낮춰 두었기
 * 때문이다 — 더 늘리면 트랜잭션을 연 채 모이는 동안 풀이 비어, 실패가 코드 결함과 구별되지 않는다.
 *
 * <p>잠금이 <b>어디까지 미치는가</b>(학원 단위인가 · 트랜잭션 밖에서도 유효한가)는 여기가 아니라
 * {@code StopMergeLockScopeTest} 가 잰다. 이 클래스는 "중복이 생기는가" 한 가지만 본다.
 *
 * <p>{@code reference.md §20.2} 의 200줄을 파일 길이로는 넘긴 채 둔다 — <b>주석과 빈 줄을 뺀 본문은
 * 184줄</b>이고, 넘는 분량은 §19 가 테스트 클래스에 허용한 서술 주석이다. 남은 넷은 같은 좌표 구역과
 * 같은 뒷정리 계약(비트랜잭션이라 만든 행을 손으로 지운다)을 공유하므로 더 가르지 않았다 — 가르면 그
 * 계약이 세 번째 클래스에 복제되고, 한쪽만 고쳐지면 남은 행이 다음 실행의 행 수 단언을 무너뜨린다.
 */
@SpringBootTest
class StopMergeConcurrencyTest {

    /** 시드 승하차지(학원 A 4곳)와 겹치지 않는 좌표를 쓴다 — 겹치면 시드 행에 붙어 행 수 단언이 무의미해진다. */
    private static final BigDecimal 기준_위도 = new BigDecimal("37.700400");

    private static final BigDecimal 기준_경도 = new BigDecimal("127.050000");

    /**
     * 기준점에서 북쪽으로 약 30m — 임계(50m) <b>안</b>이라 병합 대상이지만 좌표는 다르다.
     *
     * <p>두 값은 <b>반올림 경계를 사이에 두도록</b> 골랐다(소수점 3·4·5자리 어디서 잘라도 다른 칸:
     * {@code 37.700}/{@code 37.701}, {@code 37.7004}/{@code 37.7007}). 잠금 범위를 좌표·격자로 좁힌
     * 구현이 이 시험을 <b>우연히 통과</b>하는 것을 줄이기 위한 것이다 — 실제로 앞선 실측에서 두 값이
     * 같은 칸에 들어가 3자리 격자 변형을 놓쳤다.
     *
     * <p>그래도 이 시험은 격자 크기와 자르는 방식(반올림·버림)에 <b>여전히 의존</b>한다. 30m 를 한 칸에
     * 담는 성긴 격자는 여기서 안 잡힌다. 격자 크기에 기대지 않는 단언은
     * {@code StopMergeLockScopeTest#같은_학원_안에서는_먼_좌표끼리도_서로_막는다} 쪽이다.
     */
    private static final BigDecimal 인접_위도 = new BigDecimal("37.700670");

    private static final long 학원_A = 1L;

    /** 뒷정리가 이 클래스가 만든 행만 지우도록 붙이는 표식 — {@code stop.address} 앞머리로 쓴다. */
    private static final String 표식 = "동시성시험";

    /** 스레드 하나가 상대를 기다리는 상한 — 정상 흐름에서는 소진되지 않는다. 걸리면 단언이 실패로 드러낸다. */
    private static final long 대기_상한_초 = 20;

    private static final long 물어보는_간격_밀리초 = 50;

    @Autowired
    private StopMatcher stopMatcher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 앞선_실행이_남긴_행을_지운다() {
        뒷정리한다();
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 표식이 붙은 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ? AND address LIKE ?", 학원_A, 표식 + "%");
    }

    /**
     * 같은 좌표로 동시에 들어온 요청 4건이 승하차지 하나를 공유한다.
     *
     * <p>단언이 둘인 이유는 서로 다른 사고를 막기 때문이다 — 반환된 식별자가 하나라는 것은 네 요청이
     * 같은 답을 받았다는 뜻이고, DB 행이 하나라는 것은 응답과 저장 상태가 갈리지 않았다는 뜻이다.
     * 잠금이 없으면 뒤엣것부터 무너진다.
     */
    @Test
    void 같은_좌표를_동시에_등록해도_승하차지가_1개만_생긴다() throws Exception {
        List<Long> 받은_식별자 = 동시에_확보한다(List.of(지점(기준_위도, 기준_경도, 표식 + " 승하차지"),
                지점(기준_위도, 기준_경도, 표식 + " 승하차지"),
                지점(기준_위도, 기준_경도, 표식 + " 승하차지"),
                지점(기준_위도, 기준_경도, 표식 + " 승하차지")));

        assertThat(new HashSet<>(받은_식별자))
                .as("네 요청이 같은 승하차지를 받아야 한다 — 둘 이상이면 같은 자리에 승하차지가 여럿 생긴 것이다")
                .hasSize(1);
        assertThat(행_수())
                .as("DB 에 남은 행도 1개여야 한다 — 응답과 저장 상태가 갈리면 노선 계산의 입력이 갈린다")
                .isEqualTo(1);
    }

    /**
     * 임계 안(30m)이지만 좌표가 다른 두 주소를 동시에 등록해도 승하차지는 1개다.
     *
     * <p><b>잠금 범위가 학원인지</b>를 재는 단언이다. 좌표·격자로 좁히면 두 점이 서로 다른 잠금을 잡아
     * 직렬화되지 않고, 그래도 위의 같은-좌표 시험은 통과하므로 이 축은 여기서만 드러난다.
     */
    @Test
    void 임계_안의_다른_좌표를_동시에_등록해도_승하차지가_1개만_생긴다() throws Exception {
        List<Long> 받은_식별자 = 동시에_확보한다(List.of(지점(기준_위도, 기준_경도, 표식 + " 기준"),
                지점(인접_위도, 기준_경도, 표식 + " 인접"),
                지점(기준_위도, 기준_경도, 표식 + " 기준"),
                지점(인접_위도, 기준_경도, 표식 + " 인접")));

        assertThat(new HashSet<>(받은_식별자))
                .as("30m 떨어진 두 좌표는 임계(50m) 안이라 한 승하차지로 묶여야 한다")
                .hasSize(1);
        assertThat(행_수()).isEqualTo(1);
    }

    /**
     * 잠금을 <b>조회보다 먼저</b> 잡으므로, 기다리는 동안 남이 만들어 커밋한 승하차지에 붙는다.
     *
     * <p>여기가 목표 8 의 "잠금이 {@code findNearbyInAcademy} 전에 잡히는가" 를 재는 자리다. 순서를
     * 뒤집어 조회 뒤에 잡으면 <b>조회는 대기 전에 이미 끝나</b> 빈 결과를 들고 있게 되고, 잠금이 풀린
     * 뒤 그 낡은 결과로 "후보 부재" 를 판정해 자기 것을 새로 만든다 — 그때 이 시험은 식별자 불일치와
     * 행 2개로 실패한다. 위의 두 동시 시험은 타이밍에 따라 그 뒤집기를 통과시킬 수 있어 이 축을
     * 대신하지 못한다.
     *
     * <p>경쟁 트랜잭션이 잠금 키를 <b>학원 기준으로 직접 계산해</b> 잡는다. 프로덕션 상수를 끌어다 쓰지
     * 않고 일부러 두 번 적은 것이다 — 상수를 공유하면 이 시험이 프로덕션 키를 <b>따라가</b> 키가 바뀌어도
     * 계속 통과한다. 두 번 적으면 키가 갈리는 순간 상대를 막지 못해 아래 첫 단언이 <b>실패로</b> 드러난다
     * (실측: 좌표 키 변형에서 그렇게 실패했다). 복식부기와 같은 이유이고, 조용히 통과하는 쪽이 아니다.
     */
    @Test
    void 잠금을_먼저_잡으므로_대기_중_커밋된_승하차지에_붙는다() throws Exception {
        CountDownLatch 잠금을_잡았다 = new CountDownLatch(1);
        AtomicBoolean 상대가_자문잠금을_기다렸다 = new AtomicBoolean();
        AtomicLong 먼저_만든_승하차지 = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Long 나중에_받은_식별자;
        try {
            Future<?> 먼저 = pool.submit(() -> 잠근_뒤_상대를_기다렸다가_만든다(잠금을_잡았다, 상대가_자문잠금을_기다렸다,
                    먼저_만든_승하차지));
            Future<Long> 나중 = pool.submit(() -> {
                잠금을_잡았다.await(대기_상한_초, TimeUnit.SECONDS);
                return 확보한다(지점(기준_위도, 기준_경도, 표식 + " 나중"));
            });
            먼저.get(대기_상한_초, TimeUnit.SECONDS);
            나중에_받은_식별자 = 나중.get(대기_상한_초, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(대기_상한_초, TimeUnit.SECONDS);
        }

        assertThat(상대가_자문잠금을_기다렸다)
                .as("나중 요청은 조회에 닿기 전에 자문 잠금에서 멈춰 있어야 한다 — 안 멈추면 잠금이 없거나 "
                        + "잠금 범위가 학원이 아니다")
                .isTrue();
        assertThat(나중에_받은_식별자)
                .as("잠금이 풀린 뒤 다시 조회하므로 먼저 만든 승하차지에 붙어야 한다 — 조회를 잠금보다 "
                        + "앞세우면 낡은 빈 결과로 판정해 자기 것을 새로 만든다")
                .isEqualTo(먼저_만든_승하차지.get());
        assertThat(행_수()).isEqualTo(1);
    }

    /**
     * 반경 안에 이미 승하차지가 있으면 그것에 붙는다 — 잠금을 넣어도 정상 경로가 그대로다(회귀).
     *
     * <p>동시성 단언만 두면 "매번 새로 만들지 않는다" 는 규칙이 조용히 사라져도 초록이다.
     */
    @Test
    void 반경_안에_기존_승하차지가_있으면_새로_만들지_않는다() {
        Long 첫_요청 = 확보한다(지점(기준_위도, 기준_경도, 표식 + " 기준"));

        Long 둘째_요청 = 확보한다(지점(인접_위도, 기준_경도, 표식 + " 인접"));

        assertThat(둘째_요청).isEqualTo(첫_요청);
        assertThat(행_수()).isEqualTo(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * 지점마다 스레드를 하나씩 두고 <b>트랜잭션을 열어 둔 채</b> 출발을 맞춰 병합을 부른다.
     *
     * <p><b>출발선이 트랜잭션 안에 있는 것이 이 도우미의 요점이다.</b> 트랜잭션 밖에서 신호만 맞추면
     * 스레드마다 트랜잭션 시작·커넥션 확보 시간이 달라, 잠금을 빼고 돌려도 앞 스레드가 커밋을 마친
     * 뒤에 뒤 스레드가 조회하는 실행이 섞인다 — 그때는 뒤 스레드가 앞의 승하차지에 붙어 <b>결함이 있는
     * 채로 통과</b>한다(실측: 3회 중 1회). 각자 짧은 조회를 한 번 지나 트랜잭션과 커넥션을 실제로 연
     * 뒤에 모이면, 출발 이후 남은 것은 조회뿐이라 조회 구간이 반드시 겹친다.
     */
    private List<Long> 동시에_확보한다(List<GeocodedPoint> 지점들) throws Exception {
        CyclicBarrier 출발선 = new CyclicBarrier(지점들.size());
        ExecutorService pool = Executors.newFixedThreadPool(지점들.size());
        List<Long> 받은_식별자 = new ArrayList<>();
        try {
            List<Future<Long>> 결과 = new ArrayList<>();
            for (GeocodedPoint 지점 : 지점들) {
                결과.add(pool.submit(() -> 트랜잭션을_열고_기다렸다가_확보한다(출발선, 지점)));
            }
            for (Future<Long> 하나 : 결과) {
                받은_식별자.add(하나.get(대기_상한_초, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(대기_상한_초, TimeUnit.SECONDS);
        }
        return 받은_식별자;
    }

    /** 트랜잭션을 실제로 연 다음 출발선에서 모이고, 신호가 떨어지면 곧바로 병합을 부른다. */
    private Long 트랜잭션을_열고_기다렸다가_확보한다(CyclicBarrier 출발선, GeocodedPoint 지점) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            try {
                출발선.await(대기_상한_초, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
            return stopMatcher.matchOrCreate(학원_A, 지점).getId();
        });
    }

    /**
     * 학원 자문 잠금을 직접 잡은 채 상대가 그 잠금을 기다리는 것을 확인하고, 그때 승하차지를 만들고 커밋한다.
     *
     * <p>상대가 기다리는 것을 <b>확인하고서야</b> 만드는 것이 이 시험의 결정성을 만든다. 신호만으로
     * 순서를 맞추면 상대의 조회가 내 커밋 뒤에 도는 경우가 생겨, 그때는 순서를 뒤집어도 통과한다.
     */
    private Void 잠근_뒤_상대를_기다렸다가_만든다(CountDownLatch 잠금을_잡았다, AtomicBoolean 상대가_기다렸다,
            AtomicLong 만든_승하차지) {
        new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock("
                    + "hashtext('stop:' || cast(? as text)))) AS 잠갔음", Integer.class, 학원_A);
            잠금을_잡았다.countDown();
            상대가_기다렸다.set(상대가_자문잠금을_기다릴_때까지_기다린다());
            만든_승하차지.set(stopMatcher.matchOrCreate(학원_A, 지점(기준_위도, 기준_경도, 표식 + " 먼저")).getId());
            return null;
        });
        return null;
    }

    /**
     * 이 데이터베이스에서 자문 잠금을 기다리는 세션이 생길 때까지 기다린다.
     *
     * <p>{@code pg_locks} 는 서버 전체를 보여주므로 데이터베이스로 거른다 — 다른 에이전트가 같은
     * PostgreSQL 을 쓰는 동안 남의 대기를 내 것으로 세면 이 시험이 거짓으로 통과한다.
     *
     * @return 상한 안에 대기가 관측되면 참
     */
    private boolean 상대가_자문잠금을_기다릴_때까지_기다린다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(대기_상한_초);
        while (System.nanoTime() < 마감) {
            Integer 대기중 = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks l"
                    + " JOIN pg_database d ON d.oid = l.database"
                    + " WHERE l.locktype = 'advisory' AND NOT l.granted AND d.datname = current_database()",
                    Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return true;
            }
            try {
                Thread.sleep(물어보는_간격_밀리초);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Long 확보한다(GeocodedPoint 지점) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> stopMatcher.matchOrCreate(학원_A, 지점).getId());
    }

    private Integer 행_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stop WHERE academy_id = ? AND address LIKE ?",
                Integer.class, 학원_A, 표식 + "%");
    }

    private static GeocodedPoint 지점(BigDecimal lat, BigDecimal lng, String 표기) {
        return new GeocodedPoint(lat, lng, 표기);
    }
}
