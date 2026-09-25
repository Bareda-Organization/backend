package src.backend.student.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;

import src.backend.run.entity.Run;

/**
 * 학생 회차 판정(§3.5 · §3.10 · §3.11)의 질의 수가 <b>학원의 그날 회차 수와 무관</b>한지 본다(BR-058).
 *
 * <p>회차마다 소속을 따로 물으면 버스가 늘수록 학부모 앱 한 번 호출의 질의가 비례해 늘어, 하원 직전
 * 동시 접속 때 DB 풀 경합의 원인이 된다. 학생과 무관한 회차를 10개 더 심고 질의 수가 그대로인지 센다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class StudentRunResolverQueryCountTest {

    private static final long ACADEMY_A = 1L;

    private static final long STUDENT_1 = 1L;

    @Autowired
    private StudentRunResolver studentRunResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void 학원의_그날_회차가_늘어도_판정_질의_수는_그대로다() {
        LocalDate date = jdbcTemplate.queryForObject("SELECT MIN(service_date) FROM run WHERE academy_id = 1",
                LocalDate.class);
        long before = statementsOf(() -> studentRunResolver.resolveAllByDate(ACADEMY_A, STUDENT_1, date));
        List<Long> beforeRuns = studentRunResolver.resolveAllByDate(ACADEMY_A, STUDENT_1, date).stream()
                .map(Run::getId).toList();
        assertThat(beforeRuns).as("학생 1 의 그날 회차가 없으면 아래 '결과가 같다' 단언이 빈 목록끼리의 비교가 된다")
                .isNotEmpty();

        // 학생 1 이 타지 않는 2호차(버스 2)에 idle 5개 · confirmed 5개를 더 심는다.
        for (int i = 1; i <= 10; i++) {
            jdbcTemplate.update("""
                    INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, confirm_at, status,
                                     origin_name, destination_name)
                    VALUES (1, 2, ?, 'to_academy', ?::date + make_interval(hours => 20, mins => ?),
                            ?::date + make_interval(hours => 20, mins => ?) - interval '30 minutes', ?, '출발', '도착')
                    """, date, date, i, date, i, i <= 5 ? "idle" : "confirmed");
        }

        long after = statementsOf(() -> studentRunResolver.resolveAllByDate(ACADEMY_A, STUDENT_1, date));

        assertThat(studentRunResolver.resolveAllByDate(ACADEMY_A, STUDENT_1, date).stream().map(Run::getId))
                .as("학생과 무관한 회차가 판정 결과에 섞였다 — 질의를 줄이다 소속 판정이 바뀌었다")
                .containsExactlyElementsOf(beforeRuns);
        assertThat(after)
                .as("회차 10개를 더하자 판정 질의가 %d → %d 로 늘었다 — 회차마다 소속을 따로 묻고 있다", before, after)
                .isEqualTo(before);
    }

    private long statementsOf(Supplier<?> call) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        call.get();
        return statistics.getPrepareStatementCount();
    }
}
