package src.backend.bus.command;

import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.dto.BusRegisterRequest;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.BusUpdateRequest;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/** 차량 등록·수정(BUS-02·03, API_SPEC §5.12). */
@Service
@RequiredArgsConstructor
@Transactional
public class BusCommandService {

    /**
     * 호차 유일성을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code bus} 에 CHECK 가 둘 더 있기 때문이다
     * ({@code ck_bus_student_capacity}·{@code ck_bus_capacity}). 제약을 가리지 않고
     * {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 정원 계산 위반까지
     * "이미 등록된 호차입니다" 로 응답해 원인을 감춘다.
     */
    private static final String BUS_NO_UNIQUE_CONSTRAINT = "uk_bus_academy_bus_no";

    private final BusRepository busRepository;

    /**
     * 차량을 등록한다(§5.12) — 학생 탑승 가능 인원은 정원에서 승무 인원을 빼 <b>서버가 계산</b>한다.
     *
     * <p>소속 학원은 토큰에서만 온다(§1.5) — 요청 본문에 학원을 담을 자리를 두지 않은 것이 그 규칙을
     * 지키는 방식이다.
     */
    public BusResponse register(AuthUser requester, BusRegisterRequest request) {
        Bus bus = Bus.register(requester.academyId(), request.busNo(), request.plateNo(),
                BusSeating.withDefaultCrew(request.capacity()));
        // operable 은 정적 팩토리가 받지 않는다 — 등록 시점의 식별 정보가 아니라 운행 중 켜고 끄는
        // 상태라, 생성 인자를 하나 더 늘리는 대신 수정과 같은 자리를 쓴다(AcademyCommandService 의
        // memo 와 같은 형태). 값을 주지 않으면 팩토리가 정한 기본값(운행 가능)이 그대로 남는다.
        bus.update(null, null, null, request.operable());
        return enforcingUniqueBusNo(requester.academyId(), request.busNo(),
                () -> BusResponse.from(busRepository.save(bus)));
    }

    /**
     * 차량 정보를 고친다(§5.12) — 대상이 다른 학원이면 {@code 404 BUS_NOT_FOUND} 다.
     *
     * <p>정원을 고치면 {@link BusSeating} 이 학생 정원을 다시 계산하며, 승무 인원은 지금 차량이 들고
     * 있는 값을 그대로 이어받는다 — 요청이 그 값을 줄 자리가 §5.12 에 부재하다.
     */
    public BusResponse update(AuthUser requester, Long busId, BusUpdateRequest request) {
        Bus bus = busRepository.findByIdAndAcademyId(busId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Supplier<BusResponse> apply = () -> {
            bus.update(request.busNo(), request.plateNo(), request.capacity(), request.operable());
            return BusResponse.from(bus);
        };
        return renamesBusNo(bus, request.busNo())
                ? enforcingUniqueBusNo(requester.academyId(), request.busNo(), apply)
                : apply.get();
    }

    /**
     * 호차를 <b>다른 값으로</b> 바꾸는 수정인가 — 보내지 않았거나 지금과 같으면 중복 판정 대상 밖이다.
     *
     * <p>같은 값을 걸러내지 않으면 자기 호차를 그대로 다시 보내는 요청이 자기 자신을 중복으로 세어
     * {@code 409} 가 된다 — 그러면 정원만 고치려고 호차를 함께 보낸 클라이언트가 막힌다.
     */
    private boolean renamesBusNo(Bus bus, String busNo) {
        return busNo != null && !busNo.equals(bus.getBusNo());
    }

    /**
     * 호차 유일성을 강제하며 작업을 실행한다 — 위반은 선검사에서든 DB 거부에서든 같은
     * {@code 409 DUPLICATE_BUS_NO} 다(Ruling 164).
     *
     * <p><b>선검사만으로는 부족하다.</b> 동시 요청 2건은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다
     * 선검사를 지나고, 그 뒤 {@code uk_bus_academy_bus_no} 가 하나를 거부한다. 그 거부를 옮기지
     * 않으면 사용자에게 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 있는 호차다" 가 구별되지
     * 않는다({@code AcademyStaffQuota} 가 같은 형태를 이미 푼다).
     *
     * <p>{@code action} 실행 후 즉시 flush 하는 것이 수정 경로 때문에 필요하다 — 호차 변경은 <b>변경
     * 감지</b>로만 DB 에 닿아서, flush 하지 않으면 {@code UPDATE} 가 커밋 시점까지 미뤄지고 제약
     * 위반이 이 {@code try} 밖에서 터진다. 등록 경로는 {@code IDENTITY} 키를 받으려고 {@code save()}
     * 시점에 이미 나가므로 flush 가 없어도 잡힌다 — <b>두 경로가 다르다.</b>
     *
     * <p>{@link jakarta.persistence.EntityManager} 가 아니라 <b>저장소의</b> flush 를 부르는 것도
     * 같은 이유로 중요하다 — 예외 번역({@code DataIntegrityViolationException})은 {@code @Repository}
     * 빈을 거칠 때만 붙어, {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를
     * 그대로 지나친다.
     */
    private <T> T enforcingUniqueBusNo(Long academyId, String busNo, Supplier<T> action) {
        if (busRepository.existsByAcademyIdAndBusNo(academyId, busNo)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BUS_NO);
        }
        try {
            T result = action.get();
            busRepository.flush();
            return result;
        } catch (DataIntegrityViolationException e) {
            if (isBusNoViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_BUS_NO);
            }
            throw e;
        }
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 호차 제약인지만 본다. */
    private boolean isBusNoViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && BUS_NO_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }
}
