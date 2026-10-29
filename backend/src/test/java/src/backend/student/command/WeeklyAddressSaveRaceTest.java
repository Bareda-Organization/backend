package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.dto.WeeklyAddressEntryRequest;
import src.backend.student.dto.WeeklyAddressUpdateRequest;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 같은 칸을 처음 저장하는 두 요청이 겹치면 둘 다 성공한다 — 두 요청이 기존 행을 읽어 "없음" 을 본 뒤 동시에 넣으면 늦은 쪽이 UNIQUE 를
 * 밟는데, 그것은 요청의 잘못이 아니라 겹침이므로 요청 안에서 한 번 다시 시도해 먼저 저장된 행을 덮어쓰는 길로 간다.
 *
 * <p>기존 행 조회가 끝난 직후 두 스레드를 한 곳에 모아(두 번째 호출까지만) "둘 다 없음을 본 뒤" 를 시험이 만든다. 트랜잭션 없이 실제
 * 커밋을 남기므로 만든 행은 직접 지운다.
 */
@SpringBootTest
@Import(RepositoryReadHooks.class)
class WeeklyAddressSaveRaceTest {

    /** 학원 B 보호자(parentB1)와 그 자녀(studentB1) — 시드에 요일별 주소가 없다. */
    private final AuthUser parent = new AuthUser(9L, 2L, Role.PARENT, AccountStatus.ACTIVE, false);

    private static final long STUDENT_ID = 6L;

    @Autowired
    private WeeklyAddressCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private long stopWatermark;

    @BeforeEach
    void 시작_전에_정리한다() {
        stopWatermark = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM stop", Long.class);
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id = ?", STUDENT_ID);
    }

    @AfterEach
    void 뒷정리한다() {
        executor.shutdownNow();
        RepositoryReadHooks.clear();
        jdbcTemplate.update("DELETE FROM weekly_address WHERE student_id = ?", STUDENT_ID);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = 2 AND id > ?", stopWatermark);
    }

    @Test
    void 같은_칸을_처음_저장하는_두_요청은_둘_다_성공한다() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        RepositoryReadHooks.afterRead("findAllByStudentIdAndAcademyId", () -> {
            if (reads.incrementAndGet() <= 2) {
                모인다(bothRead);
            }
        });
        WeeklyAddressUpdateRequest request = new WeeklyAddressUpdateRequest(
                List.of(new WeeklyAddressEntryRequest("mon", "to_academy", "서울시 테스트로 100", null)));

        Future<?> first = executor.submit(() -> commandService.replace(parent, STUDENT_ID, request));
        Future<?> second = executor.submit(() -> commandService.replace(parent, STUDENT_ID, request));

        assertCompletes(first);
        assertCompletes(second);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM weekly_address WHERE student_id = ?",
                Integer.class, STUDENT_ID)).isEqualTo(1);
    }

    private static void assertCompletes(Future<?> request)
            throws InterruptedException, ExecutionException, TimeoutException {
        request.get(30, TimeUnit.SECONDS);
    }

    private static void 모인다(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("두 요청이 기존 행 조회 뒤에 모이지 못했다", e);
        }
    }
}
