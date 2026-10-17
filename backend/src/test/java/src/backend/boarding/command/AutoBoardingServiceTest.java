package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.boarding.entity.RunRider;

/**
 * 하원 회차 시작 시 명단 전원 일괄 승차(C-07 · BRD-03) — {@link AutoBoardingService} 를 직접
 * 호출한다. 회차 시작 경유(알림·이력)는 {@code RunBoardingNotificationTest} 가 다룬다.
 */
@SpringBootTest
@Transactional
class AutoBoardingServiceTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AutoBoardingService autoBoardingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;


    private BoardingCommandFixtures fixtures;

    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = BoardingCommandFixtures.of(applicationContext, entityManager);
        }
        return fixtures;
    }

    @Test
    @DisplayName("목표11 — 하원 회차 시작 시 대기 중인 탑승자 전원이 자동 승차 처리된다")
    void 하원_시작_시_대기중인_탑승자_전원이_자동_승차된다() {
        OffsetDateTime now = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long waitingStudent1 = fixtures().student(academyId, "학생1");
        long waitingStudent2 = fixtures().student(academyId, "학생2");
        long alreadyNoShowStudent = fixtures().student(academyId, "학생3");
        long runId = fixtures().run(academyId, busId, now.minusMinutes(30), now.minusMinutes(60));
        long riderWaiting1 = fixtures().runRider(runId, waitingStudent1, stopId);
        long riderWaiting2 = fixtures().runRider(runId, waitingStudent2, stopId);
        long riderNoShow = fixtures().runRider(runId, alreadyNoShowStudent, stopId);
        // 이미 미승차로 빠진 탑승자는 하원 시작 시점에도 그대로 둔다(AutoBoardingService 자바독) — 그
        // 판단을 검증 가능한 상태로 만들기 위해 이력 없이 직접 상태만 no_show 로 앞당겨 둔다.
        jdbcTemplate.update("UPDATE run_rider SET status = 'no_show' WHERE id = ?", riderNoShow);
        entityManager.clear();

        List<RunRider> boarded = autoBoardingService.boardAllForDropOff(runId, now);

        assertThat(boarded).as("①응답 계산값 — 대기 중이던 2명만 승차 처리된다").extracting(RunRider::getId)
                .containsExactlyInAnyOrder(riderWaiting1, riderWaiting2);

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderWaiting1)).as("②저장값").isEqualTo("boarded");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderWaiting2)).as("②저장값").isEqualTo("boarded");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class,
                riderNoShow)).as("②저장값 — 이미 미승차인 탑승자는 건드리지 않는다").isEqualTo("no_show");
    }
}
