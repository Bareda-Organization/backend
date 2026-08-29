package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 승하차지 병합 잠금이 <b>어디까지 미치는가</b> — 학원 하나이지 좌표도 전역도 아니다(Ruling 179 항목 1).
 *
 * <p>범위는 양쪽에서 눌러야 고정된다. 좁히면(좌표·격자) 임계 안인데 다른 칸에 놓인 두 점이 서로 다른
 * 잠금을 잡아 중복이 생기고, 넓히면(전역) 학원 하나의 등록이 다른 모든 학원의 등록을 멈춰 세운다.
 * <b>한쪽만 재는 단언은 반대쪽으로 새는 변경을 통과시킨다</b> — 전역 키는 "같은 학원" 시험을 오히려
 * 더 확실히 통과시키고, 좌표 키는 "다른 학원" 시험을 그대로 통과시킨다.
 *
 * <p>{@code StopMergeConcurrencyTest} 의 격자 경계 시험과 역할이 다르다. 그쪽은 30m 떨어진 두 점을
 * 쓰므로 <b>격자가 그 둘을 가를 만큼 촘촘할 때만</b> 좌표 좁힘을 잡는다(실측: 소수점 3자리 칸에서는
 * 놓치고 4자리에서만 잡는다). 여기 §1 은 5km 떨어진 두 점을 쓰므로 그보다 촘촘한 <b>모든</b> 격자에서
 * 잡는다 — 격자 크기에 기대지 않는 것이 이 클래스의 존재 이유다.
 *
 * <p>{@code @Transactional} 이 부재한 것은 {@code StopMergeConcurrencyTest} 와 같은 이유다 — 테스트가
 * 트랜잭션을 하나 열고 있으면 두 스레드가 그것을 공유해 잠금 경합 자체가 만들어지지 않는다.
 */
@SpringBootTest
class StopMergeLockScopeTest {

    private static final long 학원_A = 1L;

    private static final long 학원_B = 2L;

    /** 시드 승하차지·다른 시험의 좌표와 겹치지 않는 자리 — 겹치면 병합이 끼어들어 잠금 관측이 흐려진다. */
    private static final BigDecimal 점유_위도 = new BigDecimal("37.710000");

    private static final BigDecimal 점유_경도 = new BigDecimal("127.060000");

    /**
     * 점유 지점에서 약 7km — 임계(50m) 밖이라 병합 대상이 아니고, <b>어떤 격자로 잘라도 다른 칸</b>이다.
     *
     * <p>거리를 크게 두는 것이 요점이다. 가까이 두면 격자가 성길 때 같은 칸에 들어가, 좌표로 좁힌
     * 구현이 우연히 직렬화되어 이 시험을 통과한다.
     */
    private static final BigDecimal 먼_위도 = new BigDecimal("37.760000");

    private static final BigDecimal 먼_경도 = new BigDecimal("127.110000");

    /** 뒷정리가 이 클래스가 만든 행만 지우도록 붙이는 표식 — {@code stop.address} 앞머리로 쓴다. */
    private static final String 표식 = "잠금범위시험";

    /** 스레드가 상대를 기다리는 상한 — 정상 흐름에서는 소진되지 않는다. 걸리면 단언이 실패로 드러낸다. */
    private static final long 대기_상한_초 = 20;

    /**
     * 점유 트랜잭션이 "상대가 끝났나" 를 기다리는 상한 — <b>막히는 경우 이만큼은 반드시 소진된다.</b>
     *
     * <p>이 값이 곧 §1 의 실행 시간이라 짧게 잡되, 상대가 단지 느려서 못 끝낸 것을 "막혔다" 로 읽지
     * 않을 만큼은 길어야 한다. 상대가 하는 일은 짧은 트랜잭션 하나다.
     */
    private static final long 상대_완료_대기_상한_초 = 3;

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

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 두 학원에 걸쳐 표식이 붙은 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM stop WHERE address LIKE ?", 표식 + "%");
    }

    /**
     * 같은 학원이면 <b>7km 떨어진 좌표라도</b> 서로 막는다 — 잠금 키가 좌표를 타지 않는다는 뜻이다.
     *
     * <p>Ruling 179 가 격자 키를 기각한 이유를 지키는 단언이다. 키에 좌표가 섞이는 순간 두 지점이 다른
     * 잠금을 잡아 이 시험이 "안 막혔다" 로 실패한다. 직렬화 자체는 Ruling 179 가 받아들인 대가이므로
     * 여기서 막히는 것은 결함이 아니라 <b>요구</b>다.
     */
    @Test
    void 같은_학원_안에서는_먼_좌표끼리도_서로_막는다() throws Exception {
        boolean 점유중에_끝났다 = 점유중에_상대가_끝났는가(학원_A, 지점(점유_위도, 점유_경도, 표식 + " 점유"),
                학원_A, 지점(먼_위도, 먼_경도, 표식 + " 먼곳"));

        assertThat(점유중에_끝났다)
                .as("같은 학원의 두 등록은 좌표가 멀어도 줄을 서야 한다 — 안 막히면 잠금 키가 좌표를 타는 "
                        + "것이고, 그때 임계 안의 두 점이 격자 경계에 놓이면 승하차지가 둘 생긴다")
                .isFalse();
    }

    /**
     * 학원이 다르면 <b>좌표가 같아도</b> 서로 막지 않는다 — 학원 단위 키를 고른 이유 자체다.
     *
     * <p>전역 키(예: {@code hashtext('stop:merge')})로 넓혀도 중복은 안 생기므로 이 태스크의 다른 시험은
     * 전부 통과한다. 그때 잃는 것은 정합성이 아니라 <b>처리량</b>이다 — 학원 하나의 학생 등록이 다른 모든
     * 학원의 등록을 멈춰 세우고, 그 상태는 느려질 뿐이라 화면에 결함으로 드러나지 않는다.
     */
    @Test
    void 학원이_다르면_좌표가_같아도_서로_막지_않는다() throws Exception {
        boolean 점유중에_끝났다 = 점유중에_상대가_끝났는가(학원_A, 지점(점유_위도, 점유_경도, 표식 + " A점유"),
                학원_B, 지점(점유_위도, 점유_경도, 표식 + " B시도"));

        assertThat(점유중에_끝났다)
                .as("학원 B 의 등록은 학원 A 가 잠금을 쥔 동안에도 끝나야 한다 — 막히면 잠금 키에서 학원이 "
                        + "빠져 전역 직렬화가 된 것이다")
                .isTrue();
    }

    /**
     * 트랜잭션 밖에서 부르면 거부한다 — 그 자리에서 막지 않으면 아무것도 막지 못한 채 초록이 된다.
     *
     * <p>{@code pg_advisory_xact_lock} 은 트랜잭션이 끝날 때 풀리므로, 트랜잭션이 없으면 문장이 끝나는
     * 즉시 풀려 임계 구역이 성립하지 않는다. 잠금의 <b>유효 범위</b>를 재는 것이라 이 클래스에 둔다.
     */
    @Test
    void 트랜잭션_밖에서_부르면_거부한다() {
        assertThatThrownBy(() -> stopMatcher.matchOrCreate(학원_A, 지점(점유_위도, 점유_경도, 표식 + " 트랜잭션밖")))
                .as("저장소 프록시가 IllegalStateException 을 Spring 예외로 옮기므로 근본 원인으로 가린다")
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(행_수())
                .as("막지 못하면 저장은 성공한다 — 저장 자체는 저장소가 자기 트랜잭션을 열어 처리하므로, "
                        + "잠금만 조용히 무력해진 채 승하차지가 남는다")
                .isEqualTo(0);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * 한 트랜잭션이 잠금을 쥔 채 버티는 동안 다른 트랜잭션의 병합이 끝나는지 본다.
     *
     * <p>시간을 재지 않고 <b>서로를 기다리게</b> 해서 판정한다. 점유 쪽은 상대가 끝났다는 신호를
     * {@link #상대_완료_대기_상한_초} 만큼 기다렸다가 커밋하므로, 상대가 막혀 있으면 그 신호가 오지 않아
     * 거짓이 된다. 시간 비교로 판정하면 느린 실행이 "막혔다" 로 잘못 읽히지만, 이 방식은 상대가
     * <b>실제로 진행했는지</b>만 본다.
     *
     * <p>막힌 경우에도 교착에 빠지지 않는다 — 점유 쪽은 상한이 지나면 그대로 커밋하고, 그때 상대가
     * 잠금을 받아 끝낸다. 두 스레드 모두 정상 종료한 뒤에 판정한다.
     *
     * @return 점유 트랜잭션이 살아 있는 동안 상대의 병합이 끝났으면 참
     */
    private boolean 점유중에_상대가_끝났는가(long 점유_학원, GeocodedPoint 점유_지점, long 시도_학원,
            GeocodedPoint 시도_지점) throws Exception {
        CountDownLatch 점유했다 = new CountDownLatch(1);
        CountDownLatch 시도가_끝났다 = new CountDownLatch(1);
        AtomicBoolean 점유중에_끝났다 = new AtomicBoolean();
        AtomicLong 시도가_받은_식별자 = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 점유 = pool.submit(() -> 점유하고_상대를_기다린다(점유_학원, 점유_지점, 점유했다,
                    시도가_끝났다, 점유중에_끝났다));
            Future<?> 시도 = pool.submit(() -> {
                점유했다.await(대기_상한_초, TimeUnit.SECONDS);
                시도가_받은_식별자.set(확보한다(시도_학원, 시도_지점));
                시도가_끝났다.countDown();
                return null;
            });
            점유.get(대기_상한_초, TimeUnit.SECONDS);
            시도.get(대기_상한_초, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(대기_상한_초, TimeUnit.SECONDS);
        }
        assertThat(시도가_받은_식별자.get())
                .as("상대의 병합이 예외로 끝났으면 '막혔다' 와 구별되지 않는다 — 판정 전에 성공을 확인한다")
                .isPositive();
        return 점유중에_끝났다.get();
    }

    /** 병합으로 잠금을 쥔 뒤 상대가 끝났다는 신호를 기다렸다가 커밋한다 — 신호가 안 오면 그대로 커밋한다. */
    private Void 점유하고_상대를_기다린다(long 학원, GeocodedPoint 지점, CountDownLatch 점유했다,
            CountDownLatch 시도가_끝났다, AtomicBoolean 점유중에_끝났다) throws InterruptedException {
        new TransactionTemplate(transactionManager).execute(status -> {
            stopMatcher.matchOrCreate(학원, 지점);
            점유했다.countDown();
            try {
                점유중에_끝났다.set(시도가_끝났다.await(상대_완료_대기_상한_초, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return null;
        });
        return null;
    }

    private Long 확보한다(long 학원, GeocodedPoint 지점) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> stopMatcher.matchOrCreate(학원, 지점).getId());
    }

    private Integer 행_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stop WHERE address LIKE ?", Integer.class,
                표식 + "%");
    }

    private static GeocodedPoint 지점(BigDecimal lat, BigDecimal lng, String 표기) {
        return new GeocodedPoint(lat, lng, 표기);
    }
}
