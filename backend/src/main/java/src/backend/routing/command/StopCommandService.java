package src.backend.routing.command;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.assembly.StopManageAssembler;
import src.backend.routing.dto.StopManageResponse;
import src.backend.routing.dto.StopUpdateRequest;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 승하차지 수정(RTE-01 · A-08, API_SPEC §5.9 · Ruling 849) — 노선 저장({@link RouteCommandService#saveStops})의 기존 승하차지 수정과
 * 같은 규칙이다: 행 하나를 고치면 그것을 가리키는 모든 노선·학생 주소에 함께 반영되고, 운행 중 회차의 노선에 서는 승하차지의 좌표는
 * 잠긴다({@link MovingRunStopLock}).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StopCommandService {

    private final StopRepository stopRepository;

    private final MovingRunStopLock movingRunStopLock;

    private final RunRepository runRepository;

    private final StopManageAssembler stopManageAssembler;

    /**
     * 보낸 필드만 고친다 — 대상이 없거나 다른 학원이면 {@code 404 STOP_NOT_FOUND}, 운행 중 노선의 좌표를 옮기면 아무것도 바꾸지 않고
     * {@code 403 CHANGE_WINDOW_CLOSED} 다(같은 요청의 이름 변경도 함께 거절).
     *
     * <p>좌표가 실제로 옮겨졌을 때만 그 학원 회차의 확정 실패 이력을 지운다 — 노선 저장과 같은 길이다(Ruling 703). 이름·주소는 노선 계산에
     * 쓰이지 않는다.
     */
    public StopManageResponse update(AuthUser requester, Long stopId, StopUpdateRequest request) {
        boolean givesCoordinates = request.givesCoordinates();
        Stop stop = stopRepository.findByIdAndAcademyId(stopId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOP_NOT_FOUND));
        BigDecimal lat = request.lat() == null ? stop.getLat() : request.lat();
        BigDecimal lng = request.lng() == null ? stop.getLng() : request.lng();
        boolean relocated = givesCoordinates && stop.movesTo(lat, lng);
        if (relocated) {
            movingRunStopLock.assertNotRelocating(requester.academyId(), List.of(stopId));
        }
        stop.relocate(request.name() == null ? stop.getName() : request.name(), request.address(), lat, lng);
        if (relocated) {
            runRepository.resetConfirmationFailures(requester.academyId());
        }
        return stopManageAssembler.assemble(requester.academyId(), List.of(stop)).get(0);
    }
}
