package src.backend.bus.command;

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

    private final BusRepository busRepository;

    /**
     * 차량을 등록한다(§5.12) — 학생 탑승 가능 인원은 정원에서 승무 인원을 빼 <b>서버가 계산</b>한다.
     *
     * <p>소속 학원은 토큰에서만 온다(§1.5) — 요청 본문에 학원을 담을 자리를 두지 않은 것이 그 규칙을
     * 지키는 방식이다.
     */
    public BusResponse register(AuthUser requester, BusRegisterRequest request) {
        assertBusNoAvailable(requester, request.busNo());
        Bus bus = Bus.register(requester.academyId(), request.busNo(), request.plateNo(),
                BusSeating.withDefaultCrew(request.capacity()));
        // operable 은 정적 팩토리가 받지 않는다 — 등록 시점의 식별 정보가 아니라 운행 중 켜고 끄는
        // 상태라, 생성 인자를 하나 더 늘리는 대신 수정과 같은 자리를 쓴다(AcademyCommandService 의
        // memo 와 같은 형태). 값을 주지 않으면 팩토리가 정한 기본값(운행 가능)이 그대로 남는다.
        bus.update(null, null, null, request.operable());
        return BusResponse.from(busRepository.save(bus));
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
        bus.update(request.busNo(), request.plateNo(), resizedSeating(bus, request.capacity()), request.operable());
        return BusResponse.from(bus);
    }

    private BusSeating resizedSeating(Bus bus, Integer capacity) {
        return capacity == null ? null
                : new BusSeating(capacity, bus.getDriverCount(), bus.getEscortCount());
    }

    /**
     * 호차 중복을 저장 전에 막는다 — {@code uk_bus_academy_bus_no} 위반은 예외 번역을 거치지 않아
     * 그대로 {@code 500} 이 된다.
     *
     * <p>⚠ {@code 422} 인 것은 §5.12 의 에러 목록에 <b>호차 중복 코드가 부재</b>하기 때문이다.
     * 의미상으로는 {@code 409} 계열이며 전용 코드 신설은 사양 개정이 필요하다.
     */
    private void assertBusNoAvailable(AuthUser requester, String busNo) {
        if (busRepository.existsByAcademyIdAndBusNo(requester.academyId(), busNo)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이미 등록된 호차입니다");
        }
    }
}
