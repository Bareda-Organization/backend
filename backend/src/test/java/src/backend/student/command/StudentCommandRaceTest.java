package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.request.Patch;
import src.backend.global.security.AuthUser;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.photo.StudentPhotoWriter;

/**
 * 같은 학생을 두 요청이 동시에 고칠 때의 행 경합 — 퇴원이 되살아나지 않고, 서로 다른 항목을 고친 두 수정이 모두 남는다.
 *
 * <p>수정이 학생을 읽은 직후 {@link StudentPhotoWriter#replace} 에서 멈춰 세워 "읽고 나서 쓰기 전" 창을 시험이 만든다. 그 창에서 다른
 * 요청을 시작해 최대 2초까지 끝나기를 기다린다 — 행을 잠그는 구현에서는 상대가 기다리다 이쪽이 커밋된 뒤에 이어지고, 잠그지 않는
 * 구현에서는 상대가 창 안에서 커밋해 버린다. 트랜잭션 없이 실제 커밋을 남기므로 만든 행은 직접 지운다.
 */
@SpringBootTest
class StudentCommandRaceTest {

    private static final String MARK = "경합시험";

    private static final String FIRST = "first-request";

    @Autowired
    private StudentCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private StudentPhotoWriter photoWriter;

    private final AuthUser staff = new AuthUser(Long.valueOf(SeedFixtures.ACCOUNT_STAFF_A_ID),
            Long.valueOf(SeedFixtures.ACADEMY_A_ID), Role.STAFF, AccountStatus.ACTIVE, false);

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable));

    private long studentId;

    @BeforeEach
    void 학생을_만든다() {
        뒷정리한다();
        studentId = commandService.register(staff,
                new StudentRegisterRequest(MARK, null, null, null, null, null, null, true), null);
    }

    @AfterEach
    void 실행기를_닫고_뒷정리한다() {
        executor.shutdownNow();
        뒷정리한다();
    }

    private void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE target_type = 'student' AND target_id IN "
                + "(SELECT id FROM student WHERE name LIKE ?)", MARK + "%");
        jdbcTemplate.update("DELETE FROM student WHERE name LIKE ?", MARK + "%");
    }

    @Test
    void 수정이_읽고_쓰는_사이에_퇴원이_커밋돼도_퇴원은_되돌려지지_않는다() throws Exception {
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch rivalDone = new CountDownLatch(1);
        멈춘다(inWindow, rivalDone);

        Future<?> patch = executor.submit(named(FIRST, () -> commandService.update(staff, studentId,
                분류_수정("A반"), null)));
        assertThat(inWindow.await(10, TimeUnit.SECONDS)).as("수정이 읽기를 마치고 멈춘다").isTrue();
        Future<?> withdraw = executor.submit(() -> {
            commandService.withdraw(staff, studentId);
            rivalDone.countDown();
        });
        patch.get(20, TimeUnit.SECONDS);
        withdraw.get(20, TimeUnit.SECONDS);

        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM student WHERE id = ?",
                java.sql.Timestamp.class, studentId))
                .as("수정이 나중에 커밋돼도 퇴원 시각을 NULL 로 되돌리면 안 된다").isNotNull();
    }

    @Test
    void 서로_다른_항목을_고치는_두_수정이_동시에_커밋돼도_둘_다_남는다() throws Exception {
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch rivalDone = new CountDownLatch(1);
        멈춘다(inWindow, rivalDone);

        Future<?> classPatch = executor.submit(named(FIRST, () -> commandService.update(staff, studentId,
                분류_수정("A반"), null)));
        assertThat(inWindow.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> notePatch = executor.submit(() -> {
            commandService.update(staff, studentId, 메모_수정("두번째 메모"), null);
            rivalDone.countDown();
        });
        classPatch.get(20, TimeUnit.SECONDS);
        notePatch.get(20, TimeUnit.SECONDS);

        List<String> row = jdbcTemplate.query("SELECT class_name, note FROM student WHERE id = ?",
                (rs, i) -> rs.getString(1) + "/" + rs.getString(2), studentId);
        assertThat(row).as("한쪽 수정이 다른 쪽을 덮어쓰면 한 항목이 NULL 로 돌아온다").containsExactly("A반/두번째 메모");
    }

    /** 첫 요청 스레드만 읽은 직후 멈춘다 — 상대가 끝나거나 2초가 지나면 이어간다. */
    private void 멈춘다(CountDownLatch inWindow, CountDownLatch rivalDone) {
        doAnswer(invocation -> {
            if (FIRST.equals(Thread.currentThread().getName())) {
                inWindow.countDown();
                rivalDone.await(2, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(photoWriter).replace(any(), any());
    }

    private static Runnable named(String threadName, Runnable body) {
        return () -> {
            Thread.currentThread().setName(threadName);
            body.run();
        };
    }

    private static StudentUpdateRequest 분류_수정(String className) {
        return new StudentUpdateRequest(null, null, null, null, null, Patch.of(className), null, null, null);
    }

    private static StudentUpdateRequest 메모_수정(String note) {
        return new StudentUpdateRequest(null, null, null, null, null, null, Patch.of(note), null, null);
    }
}
