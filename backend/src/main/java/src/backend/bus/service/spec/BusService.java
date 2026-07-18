package src.backend.bus.service.spec;

import java.util.List;

import src.backend.bus.dto.AssignmentRequest;
import src.backend.bus.dto.BusDetailResponse;
import src.backend.bus.dto.BusResponse;
import src.backend.bus.dto.CreateBusRequest;
import src.backend.global.security.AuthUser;

/**
 * 버스 관리(관리자)의 계약(인터페이스) — 목록/상세/생성/배차.
 *
 * <p>학원 격리 규칙이 바뀌거나 배차 로직을 확장해도 컨트롤러는 이 계약만 알면 되도록 분리한다.
 * 실제 구현은 {@link BusServiceImpl}.
 */
public interface BusService {

    /** 학원 버스 목록(탑승 인원 포함). 학원 격리는 구현체에서 검증한다. */
    List<BusResponse> listBuses(AuthUser admin, Long tenantId);

    /** 버스 상세 — 노선·기사·탑승/정원·명단. */
    BusDetailResponse getBus(AuthUser admin, Long busId);

    /** 버스 생성. */
    BusResponse createBus(AuthUser admin, CreateBusRequest req);

    /** 배차 변경 — 담당 기사·운행 노선 배정. */
    BusResponse assign(AuthUser admin, Long busId, AssignmentRequest req);
}
