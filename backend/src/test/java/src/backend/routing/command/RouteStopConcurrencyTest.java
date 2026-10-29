package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
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

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteOptimizeRequest;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteStopsSaveRequest;
import src.backend.routing.dto.RouteUpdateRequest;

/**
 * 한 노선의 정차 목록을 두 요청이 동시에 갈아 끼울 때 — 오류 없이 나중에 잠금을 얻은 요청의 순서가 남고,
 * 최적화는 잠금을 얻은 뒤에 읽은 순서에서 출발한다.
 *
 * <p>먼저 들어간 트랜잭션은 상대가 DB 잠금을 기다리는 것을 확인한 뒤에야 커밋한다 — 신호만으로 순서를
 * 맞추면 상대가 커밋 뒤에 읽기 시작해 잠금이 없어도 통과한다. 이 클래스에 {@code @Transactional} 이 없는 것이
 * 요점이다(두 스레드가 서로의 미커밋 행을 보지 못해야 한다). 만든 행은 직접 지운다.
 */
@SpringBootTest
class RouteStopConcurrencyTest {

    private static final long BUS_A_ID = 2L;

    private static final String WEEKDAY = "thu";

    private static final String ROUTE_NAME = "정차교체동시성편성";

    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private RouteCommandService routeCommandService;

    @Autowired
    private RouteOptimizeService routeOptimizeService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void 자기_편성_행을_지운다() {
        jdbcTemplate.update("DELETE FROM route WHERE name = ?", ROUTE_NAME);
    }

    @Test
    void 같은_노선의_정차_저장_2건이_겹쳐도_오류_없이_나중_요청의_순서가_남는다() throws Exception {
        long routeId = 편성한다(List.of(1L, 2L, 3L, 4L));
        CountDownLatch 먼저_바꿨다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 먼저 = pool.submit(() -> 트랜잭션(() -> {
                routeCommandService.saveStops(관계자A(), routeId, 저장요청(List.of(4L, 3L, 2L, 1L)));
                먼저_바꿨다.countDown();
                상대가_잠금을_기다릴_때까지_커밋을_미룬다();
            }));
            Future<?> 나중 = pool.submit(() -> {
                먼저_바꿨다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                트랜잭션(() -> routeCommandService.saveStops(관계자A(), routeId, 저장요청(List.of(2L, 1L, 3L, 4L))));
                return null;
            });
            먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(저장된_순서(routeId))
                .as("둘 다 성공해야 하고, 잠금을 나중에 얻은 요청의 순서가 빈틈 없는 seq 로 남아야 한다")
                .containsExactly(2L, 1L, 3L, 4L);
    }

    @Test
    void 최적화는_겹친_저장이_커밋한_순서를_잠금_뒤에_읽어_그_순서에서_출발한다() throws Exception {
        long routeId = 편성한다(List.of(1L, 2L, 3L, 4L));
        CountDownLatch 먼저_바꿨다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        RouteDetailResponse optimized;
        try {
            Future<?> 저장 = pool.submit(() -> 트랜잭션(() -> {
                routeCommandService.saveStops(관계자A(), routeId, 저장요청(List.of(3L, 1L, 2L, 4L)));
                먼저_바꿨다.countDown();
                상대가_잠금을_기다릴_때까지_커밋을_미룬다();
            }));
            Future<RouteDetailResponse> 최적화 = pool.submit(() -> {
                먼저_바꿨다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return routeOptimizeService.optimize(관계자A(), routeId, new RouteOptimizeRequest(null, null, List.of(3L)));
            });
            저장.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            optimized = 최적화.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(optimized.stops().getFirst().stopId())
                .as("3번 승하차지를 고정했으니 잠금 뒤에 읽은 순서(3번이 첫째)에서 출발해 첫째 자리를 지켜야 한다")
                .isEqualTo(3L);
        assertThat(저장된_순서(routeId)).hasSize(4).first().isEqualTo(3L);
    }

    @Test
    void 정차_목록을_바꾸는_수정과_저장이_겹쳐도_오류_없이_나중_요청의_순서가_남는다() throws Exception {
        long routeId = 편성한다(List.of(1L, 2L, 3L, 4L));
        CountDownLatch 먼저_바꿨다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 먼저 = pool.submit(() -> 트랜잭션(() -> {
                routeCommandService.update(관계자A(), routeId,
                        new RouteUpdateRequest(null, null, null, null, null, List.of(4L, 3L, 2L, 1L)));
                먼저_바꿨다.countDown();
                상대가_잠금을_기다릴_때까지_커밋을_미룬다();
            }));
            Future<?> 나중 = pool.submit(() -> {
                먼저_바꿨다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                트랜잭션(() -> routeCommandService.saveStops(관계자A(), routeId, 저장요청(List.of(2L, 1L, 3L, 4L))));
                return null;
            });
            먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(저장된_순서(routeId)).containsExactly(2L, 1L, 3L, 4L);
    }

    private long 편성한다(List<Long> stopIds) {
        RouteDetailResponse created = routeCommandService.register(관계자A(),
                new RouteRegisterRequest(BUS_A_ID, WEEKDAY, "to_academy", ROUTE_NAME, null, stopIds));
        return created.id();
    }

    private List<Long> 저장된_순서(long routeId) {
        return jdbcTemplate.queryForList("SELECT stop_id FROM route_stop WHERE route_id = ? ORDER BY seq", Long.class,
                routeId);
    }

    /** 시드 승하차지 1~4 를 값 그대로 다시 보낸다 — 이름·좌표를 바꾸면 다른 시험이 읽는 시드가 달라진다. */
    private RouteStopsSaveRequest 저장요청(List<Long> order) {
        return new RouteStopsSaveRequest(order.stream().map(this::시드_항목).toList());
    }

    private RouteStopsSaveRequest.Item 시드_항목(long stopId) {
        return switch ((int) stopId) {
            case 1 -> new RouteStopsSaveRequest.Item(1L, "중앙로 스타빌딩 앞", "서울시 중앙로 1", new BigDecimal("37.566500"),
                    new BigDecimal("126.978000"));
            case 2 -> new RouteStopsSaveRequest.Item(2L, "한빛아파트 정문", "서울시 한빛로 20", new BigDecimal("37.567500"),
                    new BigDecimal("126.979000"));
            case 3 -> new RouteStopsSaveRequest.Item(3L, "그린빌라 입구", "서울시 그린로 33", new BigDecimal("37.568500"),
                    new BigDecimal("126.980000"));
            default -> new RouteStopsSaveRequest.Item(4L, "코스모스마트 앞", "서울시 코스모스로 5", new BigDecimal("37.569500"),
                    new BigDecimal("126.981000"));
        };
    }

    private void 트랜잭션(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    /**
     * 상대 세션이 DB 잠금을 기다리는 상태가 될 때까지 커밋을 미룬다 — 상한에 걸리면 그대로 커밋하고 뒤의 단언이 실패로
     * 드러낸다. 통계 스냅숏은 트랜잭션 안에서 캐시되므로 조회마다 비운다.
     */
    private void 상대가_잠금을_기다릴_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS - 10);
        while (System.nanoTime() < 마감) {
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private AuthUser 관계자A() {
        return new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
    }
}
