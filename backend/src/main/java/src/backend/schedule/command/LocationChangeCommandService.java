package src.backend.schedule.command;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.drivesession.repository.spec.DriveSessionRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
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
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

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
 */
@Service
public class LocationChangeCommandService {

    private final LocationChangeRequestRepository locationChangeRequestRepository;
    private final StudentGuardianRepository studentGuardianRepository;
    private final DriveSessionRepository driveSessionRepository;
    private final RoutePlanRepository routePlanRepository;
    private final RoutePlanSimulationService simulationService;
    private final RoutingCommandService routingCommandService;
    private final ApplicationEventPublisher eventPublisher;

    public LocationChangeCommandService(LocationChangeRequestRepository locationChangeRequestRepository,
                                        StudentGuardianRepository studentGuardianRepository,
                                        DriveSessionRepository driveSessionRepository,
                                        RoutePlanRepository routePlanRepository,
                                        RoutePlanSimulationService simulationService,
                                        RoutingCommandService routingCommandService,
                                        ApplicationEventPublisher eventPublisher) {
        this.locationChangeRequestRepository = locationChangeRequestRepository;
        this.studentGuardianRepository = studentGuardianRepository;
        this.driveSessionRepository = driveSessionRepository;
        this.routePlanRepository = routePlanRepository;
        this.simulationService = simulationService;
        this.routingCommandService = routingCommandService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public LocationChangeRequestResponse create(AuthUser parent, CreateLocationChangeRequest req) {
        Student student = requireGuardianOf(parent, req.studentId());   // 1. 보호자 검증(아니면 FORBIDDEN)
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

    private Student requireGuardianOf(AuthUser parent, Long studentId) {
        return studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .filter(s -> s.getId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "자녀가 아닙니다"));
    }
}
