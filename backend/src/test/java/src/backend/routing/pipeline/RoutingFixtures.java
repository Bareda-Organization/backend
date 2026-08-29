package src.backend.routing.pipeline;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
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
 * 노선 계산 시험이 쓰는 실제 행 — 학원 · 학생 · 승하차지 · 요일별 주소를 정상 경로의 팩토리로 만든다.
 *
 * <p>{@code INSERT} 를 직접 쓰지 않는 이유는 이 시험이 재는 것이 <b>정상 경로로 쌓인 데이터</b>를
 * 계산이 읽어 내는가이기 때문이다. 손으로 넣으면 팩토리가 강제하는 조건(검증 통과분만
 * {@code stop_id} 를 갖는다)을 우회한 행이 생겨, 좌표 미확보 분리가 실제로 무엇을 가르는지가
 * 시험 데이터의 모양에 매달린다.
 */
class RoutingFixtures {

    /** 학원 코드는 UNIQUE 라 같은 DB 를 쓰는 다른 시험과 겹치지 않게 실행마다 다른 값을 쓴다. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final AcademyRepository academyRepository;

    private final StudentRepository studentRepository;

    private final StopRepository stopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    RoutingFixtures(AcademyRepository academyRepository, StudentRepository studentRepository,
            StopRepository stopRepository, WeeklyAddressRepository weeklyAddressRepository) {
        this.academyRepository = academyRepository;
        this.studentRepository = studentRepository;
        this.stopRepository = stopRepository;
        this.weeklyAddressRepository = weeklyAddressRepository;
    }

    long academy() {
        String code = "P6T4" + SEQUENCE.incrementAndGet() + "-" + System.nanoTime();
        return academyRepository.save(Academy.register(code, "노선계산시험학원", "서울", null, null)).getId();
    }

    long student(long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
    }

    long stop(long academyId, String lat, String lng) {
        Stop stop = Stop.forVerifiedAddress(academyId, "승하차지" + lat, "서울시 어딘가 " + lat,
                new BigDecimal(lat), new BigDecimal(lng));
        return stopRepository.save(stop).getId();
    }

    /** 주소 검증을 통과해 승하차지까지 매칭된 칸 — 노선 계산이 읽는 유일한 정상 상태다. */
    void verifiedAddress(long studentId, long stopId, String lat, String lng) {
        VerifiedAddressEntry entry = new VerifiedAddressEntry(
                new VerifiedAddressEntry.AddressSlot(Weekday.MON, Direction.TO_ACADEMY),
                new VerifiedAddressEntry.AddressText("서울시 어딘가 " + lat, null),
                new GeocodedPoint(new BigDecimal(lat), new BigDecimal(lng), "서울시 어딘가"));
        weeklyAddressRepository.save(WeeklyAddress.verified(studentId, entry, stopId, OffsetDateTime.now()));
    }

    /** 주소는 적었으나 검증 전이라 좌표·승하차지가 비어 있는 칸 — 이 학생이 분리 대상이다. */
    void unverifiedAddress(long studentId) {
        weeklyAddressRepository.save(WeeklyAddress.register(studentId, Weekday.MON, Direction.TO_ACADEMY,
                "서울시 검증전주소", null, OffsetDateTime.now()));
    }
}
