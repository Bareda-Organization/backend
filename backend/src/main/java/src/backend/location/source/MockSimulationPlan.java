package src.backend.location.source;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.route.entity.Stop;
import src.backend.route.repository.spec.StopRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * Mock 시뮬레이션의 "이동 계획"을 DB 에서 한 번 읽어 평범한 값 객체({@link Leg})로 뽑아내는 컴포넌트.
 *
 * <p>왜 별도 빈으로 두나: 여기서 학생·버스·노선의 lazy 연관을 접근하므로 트랜잭션이 필요하다.
 * 만약 {@link MockLocationSource} 안에서 {@code @Transactional} 메서드를 직접 호출하면
 * 자기호출(self-invocation)이라 프록시를 못 타 트랜잭션이 안 걸린다 — 그래서 로딩을 이 빈으로 분리해
 * 외부 호출로 만든다. 반환값은 엔티티가 아니라 좌표만 담은 record 라 lazy 예외도 원천 차단된다.
 */
@Component
public class MockSimulationPlan {

    private final StudentRepository studentRepository;
    private final StopRepository stopRepository;

    public MockSimulationPlan(StudentRepository studentRepository, StopRepository stopRepository) {
        this.studentRepository = studentRepository;
        this.stopRepository = stopRepository;
    }

    /**
     * 시뮬레이션 대상(배정 버스·노선·승차 정류장이 모두 있는 학생)의 이동 구간을 만든다.
     * 출발 = 학생의 승차 정류장, 도착 = 노선의 마지막 정류장(seq 최대 = 학원).
     */
    @Transactional(readOnly = true)
    public List<Leg> build() {
        List<Leg> legs = new ArrayList<>();
        for (Student student : studentRepository.findAll()) {
            if (student.getAssignedBus() == null || student.getBoardingStop() == null) {
                continue;
            }
            var route = student.getAssignedBus().getRoute();
            if (route == null) {
                continue;
            }
            List<Stop> stops = stopRepository.findByRouteIdOrderBySeqAsc(route.getId());
            if (stops.isEmpty()) {
                continue;
            }
            Stop start = student.getBoardingStop();
            Stop end = stops.get(stops.size() - 1);   // seq 오름차순 → 마지막 = 목적지(학원)
            legs.add(new Leg(student.getId(), student.getTenant().getId(),
                    start.getLat(), start.getLng(), end.getLat(), end.getLng()));
        }
        return legs;
    }

    /** 한 학생의 이동 구간(출발→도착 좌표). */
    public record Leg(Long studentId, Long tenantId,
                      double startLat, double startLng,
                      double endLat, double endLng) {
    }
}
