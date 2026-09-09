package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * ①단계가 <b>그날의</b> 승하차지를 고르는지 본다 — 일일 변경(P-06) 우선순위와 학원 격리 둘 다
 * 어긋나도 계산은 정상적으로 끝나므로, 결과 좌표를 직접 보지 않으면 드러나지 않는다.
 */
@SpringBootTest
@Transactional
class DailyStopResolverTest {

    @Autowired
    private DailyStopResolver resolver;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    private RoutingFixtures fixtures;

    private long academyId;

    private long studentId;

    private long weeklyStopId;

    @BeforeEach
    void seedOneStudentWithWeeklyAddress() {
        fixtures = new RoutingFixtures(academyRepository, studentRepository, stopRepository,
                weeklyAddressRepository);
        academyId = fixtures.academy();
        weeklyStopId = fixtures.stop(academyId, "37.500000", "127.000000");
        studentId = fixtures.student(academyId, "학생");
        fixtures.verifiedAddress(studentId, weeklyStopId, "37.500000", "127.000000");
    }

    @Test
    @DisplayName("일일 변경이 있으면 요일별 주소 대신 그 승하차지에 선다")
    void prefersDailyChangeOverWeeklyAddress() {
        long dailyStopId = fixtures.stop(academyId, "37.560000", "127.060000");

        DailyStopResolution resolution = resolver.resolve(new DailyRoster(academyId, Weekday.MON,
                Direction.TO_ACADEMY, List.of(studentId), Map.of(studentId, dailyStopId)));

        assertThat(resolution.stops()).extracting(OrderableStop::stopId).containsExactly(dailyStopId);
        assertThat(resolution.stops()).extracting(OrderableStop::stopId).doesNotContain(weeklyStopId);
    }

    @Test
    @DisplayName("다른 학원의 승하차지를 가리키면 좌표를 얻지 못한 것으로 분리된다")
    void separatesStudentPointingAtAnotherAcademyStop() {
        long otherAcademyStopId = fixtures.stop(fixtures.academy(), "37.560000", "127.060000");

        DailyStopResolution resolution = resolver.resolve(new DailyRoster(academyId, Weekday.MON,
                Direction.TO_ACADEMY, List.of(studentId), Map.of(studentId, otherAcademyStopId)));

        assertThat(resolution.stops()).isEmpty();
        assertThat(resolution.unresolvedStudentIds()).containsExactly(studentId);
    }

    @Test
    @DisplayName("같은 승하차지의 학생은 한 자리로 합쳐지고 인원이 함께 실린다")
    void mergesStudentsSharingOneStop() {
        long second = fixtures.student(academyId, "같은자리");
        fixtures.verifiedAddress(second, weeklyStopId, "37.500000", "127.000000");

        DailyStopResolution resolution = resolver.resolve(DailyRoster.of(academyId, Weekday.MON,
                Direction.TO_ACADEMY, List.of(studentId, second)));

        assertThat(resolution.stops()).singleElement()
                .extracting(OrderableStop::stopId, OrderableStop::riderCount)
                .containsExactly(weeklyStopId, 2);
    }

    @Test
    @DisplayName("다른 방향의 주소만 있으면 그날 그 회차에서는 분리된다")
    void separatesStudentWhoOnlyHasTheOppositeDirection() {
        DailyStopResolution resolution = resolver.resolve(DailyRoster.of(academyId, Weekday.MON,
                Direction.FROM_ACADEMY, List.of(studentId)));

        assertThat(resolution.stops()).isEmpty();
        assertThat(resolution.unresolvedStudentIds()).containsExactly(studentId);
    }

    @Test
    @DisplayName("같은 학생이 명단에 두 번 실려도 정차지 인원이 부풀지 않는다 (Phase 7 목표 11)")
    void doesNotInflateRiderCountWhenSameStudentDuplicated() {
        DailyStopResolution resolution = resolver.resolve(DailyRoster.of(academyId, Weekday.MON,
                Direction.TO_ACADEMY, List.of(studentId, studentId, studentId)));

        assertThat(resolution.stops()).singleElement()
                .extracting(OrderableStop::stopId, OrderableStop::riderCount)
                .containsExactly(weeklyStopId, 1);
    }
}
