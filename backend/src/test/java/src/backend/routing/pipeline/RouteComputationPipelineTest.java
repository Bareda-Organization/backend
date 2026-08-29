package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.impl.StubMapRouteClient;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 계산 파이프라인을 <b>실제 행</b>으로 종단 검증한다(Phase 6 목표 2 · 6) — 좌표 미확보 학생의 분리와
 * 계산 스냅샷 4항이 한 흐름에서 함께 나오는지 본다.
 *
 * <p>둘을 한 클래스에 둔 이유는 재료가 같기 때문이다. 분리가 일어난 <b>바로 그 산출물</b>에 스냅샷이
 * 실려야 하고, 분리된 회차의 스냅샷만 비는 사고는 두 조건을 따로 세운 시험에서는 드러나지 않는다.
 */
@SpringBootTest
@Transactional
class RouteComputationPipelineTest {

    private static final OffsetDateTime DEPART_AT =
            OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9));

    private static final GeoPoint ORIGIN = point("37.490000", "126.990000");

    private static final GeoPoint DESTINATION = point("37.530000", "127.030000");

    @Autowired
    private RouteComputationPipeline pipeline;

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

    private List<Long> roster;

    private List<Long> unresolvableStudents;

    private List<Long> stopIds;

    @BeforeEach
    void seedFiveStudentsWithThreeStops() {
        fixtures = new RoutingFixtures(academyRepository, studentRepository, stopRepository,
                weeklyAddressRepository);
        academyId = fixtures.academy();
        long first = fixtures.stop(academyId, "37.500000", "127.000000");
        long second = fixtures.stop(academyId, "37.510000", "127.010000");
        long third = fixtures.stop(academyId, "37.520000", "127.020000");
        stopIds = List.of(first, second, third);

        long resolvedOne = fixtures.student(academyId, "좌표있음1");
        long resolvedTwo = fixtures.student(academyId, "좌표있음2");
        long resolvedThree = fixtures.student(academyId, "좌표있음3");
        fixtures.verifiedAddress(resolvedOne, first, "37.500000", "127.000000");
        fixtures.verifiedAddress(resolvedTwo, second, "37.510000", "127.010000");
        fixtures.verifiedAddress(resolvedThree, third, "37.520000", "127.020000");

        long noAddress = fixtures.student(academyId, "주소없음");
        long unverified = fixtures.student(academyId, "검증전");
        fixtures.unverifiedAddress(unverified);
        unresolvableStudents = List.of(noAddress, unverified);
        roster = List.of(resolvedOne, resolvedTwo, resolvedThree, noAddress, unverified);
    }

    @Test
    @DisplayName("좌표를 얻지 못한 학생은 분리되고 나머지로 계산이 끝난다")
    void separatesUnresolvedStudentsAndComputesTheRest() {
        RouteComputation computation = pipeline.compute(inputFrom(ORIGIN));

        assertThat(computation.unresolvedStudentIds())
                .containsExactlyInAnyOrderElementsOf(unresolvableStudents);
        assertThat(computation.stops()).extracting(OrderedStop::stopId)
                .containsExactlyInAnyOrderElementsOf(stopIds);
    }

    @Test
    @DisplayName("좌표 미확보가 있어도 예외를 던지지 않는다")
    void doesNotFailWhenSomeStudentsAreUnresolved() {
        assertThatCode(() -> pipeline.compute(inputFrom(ORIGIN))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("계산 스냅샷 4항이 결과에 실린다")
    void carriesComputationSnapshot() {
        ComputationSnapshot snapshot = pipeline.compute(inputFrom(ORIGIN)).snapshot();

        assertThat(snapshot.engineName()).isEqualTo("heuristic");
        assertThat(snapshot.trigger()).isEqualTo(RouteVersionSource.CONFIRM_BATCH);
        assertThat(snapshot.fallbackUsed()).isFalse();
        assertThat(snapshot.policySnapshot())
                .containsEntry("objective", "total_distance")
                .containsEntry("caller", CallerPolicy.BATCH.name())
                .containsEntry("mapTimeoutMs", 5000L)
                .containsEntry("dwellSecondsPerStop", 0);
    }

    @Test
    @DisplayName("지도 API 폴백으로 계산되면 그 사실이 스냅샷에 실린다")
    void marksFallbackUsedInSnapshot() {
        GeoPoint unavailable = new GeoPoint(StubMapRouteClient.UNAVAILABLE_MARKER_LAT,
                new BigDecimal("127.000000"));

        assertThat(pipeline.compute(inputFrom(unavailable)).snapshot().fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("도착 예정 시각은 정차지마다 하나씩이고 도착지 구간은 포함하지 않는다")
    void schedulesOneEtaPerStopBeforeTheDestinationLeg() {
        RouteComputation computation = pipeline.compute(inputFrom(ORIGIN));

        assertThat(computation.etas()).hasSameSizeAs(computation.stops()).isSorted();
        assertThat(computation.etas().getLast())
                .isBefore(DEPART_AT.plusMinutes(computation.estDurationMin()));
        assertThat(computation.estDistanceKm().scale()).isEqualTo(2);
    }

    private RouteComputationInput inputFrom(GeoPoint origin) {
        return new RouteComputationInput(
                DailyRoster.of(academyId, Weekday.MON, Direction.TO_ACADEMY, roster),
                origin, DESTINATION, List.of(), DEPART_AT,
                new ComputationPolicy(Duration.ofSeconds(5), CallerPolicy.BATCH,
                        RouteVersionSource.CONFIRM_BATCH));
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
