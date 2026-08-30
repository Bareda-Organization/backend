package src.backend.run.command;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.domain.VerifiedAddressEntry;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.entity.WeeklyAddress;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 배치 시험이 쓰는 실제 행 — {@code RoutingFixtures}(Phase 6)와 같은 이유로 정상 경로의
 * 팩토리로 쌓는다: 확정 배치가 읽어 내는 대상이 <b>정상 경로로 쌓인 데이터</b>이기 때문이다.
 */
class RunConfirmationFixtures {

    /** 학원 코드·차량 번호는 UNIQUE 라 같은 DB 를 쓰는 다른 시험과 겹치지 않게 실행마다 다른 값을 쓴다. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final BusRepository busRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final StudentRepository studentRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final RunRepository runRepository;

    RunConfirmationFixtures(AcademyRepository academyRepository, BusRepository busRepository,
            RouteRepository routeRepository, RouteStopRepository routeStopRepository, StopRepository stopRepository,
            StudentRepository studentRepository, WeeklyAddressRepository weeklyAddressRepository,
            RunRepository runRepository) {
        this.academyRepository = academyRepository;
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.stopRepository = stopRepository;
        this.studentRepository = studentRepository;
        this.weeklyAddressRepository = weeklyAddressRepository;
        this.runRepository = runRepository;
    }

    /** 좌표가 등록된 학원(정상 경로) — 확정 배치의 기준점으로 쓸 수 있다. */
    long academyWithCoordinates() {
        Academy academy = academyRepository.save(register());
        academy.assignCoordinates(new BigDecimal("37.500000"), new BigDecimal("127.000000"));
        return academyRepository.save(academy).getId();
    }

    /** 좌표 미등록 학원(목표 5) — {@code assignCoordinates} 를 부르지 않은 채로 남긴다. */
    long academyWithoutCoordinates() {
        return academyRepository.save(register()).getId();
    }

    private Academy register() {
        String code = "P7T2" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return Academy.register(code, "확정배치시험학원", "서울", null, null);
    }

    long bus(long academyId) {
        String busNo = "확정배치" + SEQUENCE.incrementAndGet();
        return busRepository.save(Bus.register(academyId, busNo, "00가0000", BusSeating.withDefaultCrew(16)))
                .getId();
    }

    long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "정차지" + lat, "서울시 어딘가 " + lat, new BigDecimal(lat),
                new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    /** 고정 노선 1개를 편성하고, 넘긴 {@code stopIds} 순서 그대로 정차 순번을 매긴다(RTE-01). */
    long route(long academyId, long busId, Weekday weekday, Direction direction, long... stopIds) {
        Route route = routeRepository
                .save(Route.register(academyId, new RoutePlan(busId, weekday, direction, "본선", true)));
        int seq = 1;
        for (long stopId : stopIds) {
            routeStopRepository.save(RouteStop.forRoute(route.getId(), stopId, seq++));
        }
        return route.getId();
    }

    long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    /** 그 요일·방향에 그 정차지를 쓰는 검증 통과 주소 — 확정 배치가 읽는 유일한 정상 상태다. */
    void verifiedAddress(long studentId, long stopId, Weekday weekday, Direction direction, String lat,
            String lng) {
        VerifiedAddressEntry entry = new VerifiedAddressEntry(new VerifiedAddressEntry.AddressSlot(weekday,
                direction), new VerifiedAddressEntry.AddressText("서울시 어딘가 " + lat, null),
                new GeocodedPoint(new BigDecimal(lat), new BigDecimal(lng), "서울시 어딘가"));
        weeklyAddressRepository.save(WeeklyAddress.verified(studentId, entry, stopId, OffsetDateTime.now()));
    }

    /**
     * idle 회차 1건 — {@code confirmAt} 을 호출자가 직접 준다(엔티티 팩토리 계약, {@code Run} javadoc).
     * {@code scheduleId} 는 이 시험 범위 밖이라 항상 {@code null}(임시 회차와 같은 형태).
     */
    long idleRun(long academyId, long busId, java.time.LocalDate serviceDate, Direction direction,
            OffsetDateTime departTime, OffsetDateTime confirmAt) {
        Run run = Run.forSchedule(academyId, busId, null, serviceDate, direction, departTime, confirmAt, "출발지",
                "도착지", null);
        return runRepository.save(run).getId();
    }
}
