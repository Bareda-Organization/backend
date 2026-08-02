package src.backend.schedule.command;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.security.AuthUser;
import src.backend.routing.command.RoutingCommandService;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.dto.RoutePlanComparison;
import src.backend.routing.dto.StudentOverride;
import src.backend.routing.dto.StudentOverride.OverrideAction;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.query.RoutePlanSimulationService;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.schedule.domain.LocationChangeThresholds;
import src.backend.schedule.dto.CreateLocationChangeRequest;
import src.backend.schedule.dto.LocationChangeRequestResponse;
import src.backend.schedule.entity.LocationChangeDecision;
import src.backend.schedule.entity.LocationChangeRequest;
import src.backend.schedule.event.LocationChangeResultEvent;
import src.backend.schedule.repository.spec.LocationChangeRequestRepository;
import src.backend.student.access.GuardianAccess;
import src.backend.student.entity.Student;

/**
 * 학부모 등하원 위치 변경 요청의 자동 판정(P2) — 단순 CRUD가 아니라 orchestration 이지만
 * 대체 구현체 후보가 아니라 인터페이스 없이 concrete 클래스로 둔다(§11.3).
 *
 * <p>관리자 승인 단계를 두지 않고 시뮬레이션 델타로 즉시 판정한다(D-H). 판정은 4가지다 —
 * BLOCKED(당일 세션 존재, I-4) · APPLIED(배차·계획 없음) · REPLANNED(임계 이내) · REJECTED(임계 초과·정원 초과).
 *
 * <p>⚠️ <b>어느 분기로 끝나든 {@link LocationChangeRequest} 를 저장한다.</b> 이 저장소에는 감사 로그
 * 테이블이 없어, 반려·차단을 남기지 않으면 "누가 무엇을 시도했는지"가 어디에도 남지 않는다.
 *
 * <p>⚠️ 인가 책임: 요청 DTO 에 busId 가 없고 대상 버스를 {@code student.getAssignedBus()} 에서 유도한다.
 * {@link RoutePlanSimulationService#compare}는 인가를 하지 않고 busId 를 그대로 신뢰하므로,
 * "그 학생의 보호자인가" 에 더해 "그 버스가 그 학생의 버스인가" 까지 이 서비스가 보장해야 한다.
 *
 * <h3>⚠️ 알려진 위험 — 쓰기 트랜잭션이 외부 지도 API 를 기다린다 (2026-08-03 확인, 미해결)</h3>
 * {@code create} 하나가 {@code @Transactional} 이고, 그 안에서 지도 API 호출이 <b>최대 2번</b> 일어난다 —
 * 4단계 {@link RoutePlanSimulationService#compare} 와 {@code republishForBus} 각각의 경로 재계산이다.
 * 한 번의 재계산도 HTTP 1회가 아니다: {@code RoutePlanComputer.resolveRoute} 가 waypoint 상한
 * ({@code routing.max-waypoints}, 기본 7)을 넘으면 구간을 나눠 여러 번 부르고, 호출당 타임아웃은
 * {@code WebClientConfig} 기준 5초다. 25인승 만석이면 REPLANNED 경로 하나가 HTTP 약 10회 —
 * 최악의 경우 수십 초 동안 <b>DB 커넥션과 쓰기 트랜잭션이 열린 채</b> 잡혀 있고, 지도 API 가 느려지면
 * 커넥션 풀 고갈로 번진다.
 *
 * <p><b>그럼에도 트랜잭션을 쪼개지 않은 이유</b>(쪼개면 지금보다 나빠진다):
 * <ul>
 *   <li><b>원자성</b> — 좌표 갱신과 노선 재배포가 한 단위다. 나누면 재계산 실패 시 "좌표는 바뀌었는데
 *       노선은 옛 것" 이 남고, 되돌릴 보상 로직이 없다. 지금은 통째로 롤백된다.</li>
 *   <li><b>dirty checking</b> — {@code applyCoordinates} 는 영속 상태 {@link Student} 를 그냥 수정한다.
 *       {@code spring.jpa.open-in-view=false} 라 트랜잭션 밖에서는 이 엔티티가 detached 이고,
 *       변경이 <b>예외 없이 조용히 사라진다</b>.</li>
 *   <li><b>읽기 순서</b> — {@code republishForBus} 는 같은 트랜잭션의 auto-flush 덕분에 방금 바꾼 좌표를
 *       본다. 경계를 나누면 "좌표 커밋이 먼저" 라는 보이지 않는 순서 제약이 생긴다.</li>
 *   <li><b>이벤트 시점</b> — {@code finish} 가 발행하는 {@link LocationChangeResultEvent} 는
 *       {@code TransactionalDomainEventRelay} 가 {@code AFTER_COMMIT} 에서만 Kafka 로 릴레이한다.
 *       트랜잭션 밖에서 발행하면 리스너가 아예 실행되지 않아 <b>학부모 결과 알림이 조용히 유실</b>된다.</li>
 *   <li><b>실효</b> — 이 메서드에서 {@code @Transactional} 을 떼도 HTTP 는 여전히 트랜잭션 안이다.
 *       {@code RoutePlanSimulationService.compare} 는 그 자체가 {@code @Transactional(readOnly=true)} 이고
 *       {@code republishForBus} 는 {@code @Transactional} 이다. 즉 이 경계만 바꿔서는 얻는 게 없다.</li>
 * </ul>
 *
 * <p><b>진짜 해법</b>은 "계산 중에는 커넥션을 쥐지 않는" 구조다 — 로스터·계획 로딩을 짧은 트랜잭션으로
 * 끝내고 지도 API 는 트랜잭션 밖에서 부른 뒤, 저장만 다시 짧은 트랜잭션으로 여는 것. 이는
 * {@code RoutePlanSimulationService}·{@code RoutingCommandService} 양쪽을 함께 손대야 해서 별도 작업으로 둔다.
 * 그때까지 <b>완화책</b>: 지도 API 타임아웃(5초)을 늘리지 말 것, 커넥션 풀 크기와
 * {@code routing.max-waypoints} 를 함께 볼 것.
 */
@Service
public class LocationChangeCommandService {

    private final LocationChangeRequestRepository locationChangeRequestRepository;
    private final GuardianAccess guardianAccess;
    private final DriveSessionRepository driveSessionRepository;
    private final RoutePlanRepository routePlanRepository;
    private final RoutePlanSimulationService simulationService;
    private final RoutingCommandService routingCommandService;
    private final ApplicationEventPublisher eventPublisher;

    public LocationChangeCommandService(LocationChangeRequestRepository locationChangeRequestRepository,
                                        GuardianAccess guardianAccess,
                                        DriveSessionRepository driveSessionRepository,
                                        RoutePlanRepository routePlanRepository,
                                        RoutePlanSimulationService simulationService,
                                        RoutingCommandService routingCommandService,
                                        ApplicationEventPublisher eventPublisher) {
        this.locationChangeRequestRepository = locationChangeRequestRepository;
        this.guardianAccess = guardianAccess;
        this.driveSessionRepository = driveSessionRepository;
        this.routePlanRepository = routePlanRepository;
        this.simulationService = simulationService;
        this.routingCommandService = routingCommandService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public LocationChangeRequestResponse create(AuthUser parent, CreateLocationChangeRequest req) {
        Student student = guardianAccess.requireGuardianOf(parent, req.studentId());   // 1. 보호자 검증(아니면 FORBIDDEN)
        Bus bus = student.getAssignedBus();
        LocalDate date = req.targetDate() != null ? req.targetDate() : LocalDate.now();

        // 2. 당일 세션이 있으면(진행중·종료 무관) 접수 자체를 거부한다 — 불변조건 I-4
        if (bus != null && driveSessionRepository
                .existsByBusIdAndDirectionAndServiceDate(bus.getId(), req.direction(), date)) {
            return finish(parent, student, req, date, LocationChangeDecision.BLOCKED, null, null,
                    "당일 운행 세션이 이미 시작돼 변경할 수 없습니다");
        }

        // 3. 배차·당일 계획이 없으면 좌표만 갱신하고 끝낸다 — 이후 관리자가 배차한다
        Optional<RoutePlan> baseline = bus == null ? Optional.empty()
                : routePlanRepository.findTopByBusIdAndDirectionOrderByVersionDesc(bus.getId(), req.direction())
                        .filter(p -> p.getServiceDate().equals(date));
        if (baseline.isEmpty()) {
            applyCoordinates(student, req);
            return finish(parent, student, req, date, LocationChangeDecision.APPLIED, null, null,
                    "배차 전이라 요청 위치를 바로 반영했습니다");
        }

        // 4. 계획이 있으면 BE-4 엔진으로 baseline vs candidate 를 비교한다(아무것도 저장하지 않는다 — I-3)
        RoutePlanComparison comparison = simulationService.compare(bus.getId(), req.direction(), date,
                List.of(new StudentOverride(student.getId(), OverrideAction.MOVE, req.lat(), req.lng())));
        RoutePlanComparison.Delta delta = comparison.delta();

        // 4-a. 정원 초과안은 임계 이내여도 적용하면 안 된다. 엔진은 정원 초과를 예외로 죽이지 않고
        //      seatCapacity 를 그대로 돌려주므로(OVERVIEW §4.4) 판정은 호출자 몫이다.
        if (comparison.candidate().stops().size() > comparison.seatCapacity()) {
            return finish(parent, student, req, date, LocationChangeDecision.REJECTED,
                    delta.distanceM(), delta.durationS(),
                    "변경 후 정차 인원(" + comparison.candidate().stops().size()
                            + "명)이 버스 정원(" + comparison.seatCapacity() + "명)을 초과해 반려했습니다");
        }

        // 4-b. AND 조건이다(D-H). 감소(음수 델타)는 항상 통과한다.
        boolean within = delta.durationS() <= LocationChangeThresholds.MAX_DELTA_DURATION_S
                && delta.distanceM() <= LocationChangeThresholds.MAX_DELTA_DISTANCE_M;
        if (!within) {
            return finish(parent, student, req, date, LocationChangeDecision.REJECTED,
                    delta.distanceM(), delta.durationS(),
                    "노선 부담이 허용치를 초과해 반려했습니다(거리 +" + Math.round(delta.distanceM())
                            + "m, 시간 +" + Math.round(delta.durationS()) + "초)");
        }

        applyCoordinates(student, req);
        Long newPlanId = routingCommandService.republishForBus(bus.getId(), req.direction(), date, parent.userId());
        return finish(parent, student, req, date, LocationChangeDecision.REPLANNED,
                delta.distanceM(), delta.durationS(), newPlanId, "노선을 재계산해 반영했습니다");
    }

    /**
     * 방향에 따라 학생 <b>자체 컬럼</b>만 건드린다(D-K).
     * <p>⚠️ {@code Stop.lat/lng} 를 절대 수정하지 않는다 — {@code Student.boardingStop} 이 가리키는 {@code Stop}
     * 은 노선에 딸린 공용 행이라, 한 학생의 등원 위치를 바꾸려고 그 좌표를 고치면 같은 정류장을 쓰는
     * 다른 학생까지 통째로 끌려간다. {@code boardingStop} 참조는 읽지도 쓰지도 않고 그대로 둔다.
     */
    private void applyCoordinates(Student student, CreateLocationChangeRequest req) {
        if (req.direction() == RouteDirection.DROPOFF) {
            student.updateDropoff(req.label(), req.lat(), req.lng());
        } else {
            student.updatePickup(req.label(), req.lat(), req.lng());   // BE-2 가 만든 pickup_* 컬럼
        }
    }

    /** 판정이 끝난 모든 경로가 여기로 합류한다 — 감사 행 저장과 결과 알림을 한 곳에서만 수행한다. */
    private LocationChangeRequestResponse finish(AuthUser parent, Student student, CreateLocationChangeRequest req,
                                                 LocalDate date, LocationChangeDecision decision,
                                                 Double deltaDistanceM, Double deltaDurationS, String reason) {
        return finish(parent, student, req, date, decision, deltaDistanceM, deltaDurationS, null, reason);
    }

    private LocationChangeRequestResponse finish(AuthUser parent, Student student, CreateLocationChangeRequest req,
                                                 LocalDate date, LocationChangeDecision decision,
                                                 Double deltaDistanceM, Double deltaDurationS,
                                                 Long appliedPlanId, String reason) {
        LocationChangeRequest saved = locationChangeRequestRepository.save(LocationChangeRequest.builder()
                .tenantId(student.getTenant().getId())
                .studentId(student.getId())
                .requestedBy(parent.userId())
                .direction(req.direction())
                .targetDate(date)
                .newLat(req.lat())
                .newLng(req.lng())
                .newAddress(req.label())
                .decision(decision)
                .deltaDistanceM(deltaDistanceM)
                .deltaDurationS(deltaDurationS)
                .appliedPlanId(appliedPlanId)
                .reason(reason)
                .build());

        eventPublisher.publishEvent(LocationChangeResultEvent.of(saved, student.getName()));
        return LocationChangeRequestResponse.from(saved);
    }
}
