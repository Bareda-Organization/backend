package src.backend.rideevent.service.spec;

import java.time.LocalDate;
import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.rideevent.dto.CorrectionRequest;
import src.backend.rideevent.dto.RecordRideRequest;
import src.backend.rideevent.dto.RideEventResponse;

/**
 * 승하차 기록의 계약(인터페이스).
 *
 * <p>하나의 기록을 4개 역할(학생 본인 / 학부모 자녀 / 기사 담당버스 / 관리자 테넌트)이 각자
 * 권한 범위에서 조회한다. 기록·정정·역할별 조회를 계약으로 노출하고, 실제 로직은
 * {@link RideEventServiceImpl} 이 담당한다(예: 정정 정책·조회 범위를 확장해도 계약은 유지).
 */
public interface RideEventService {

    /** 기사가 담당 버스의 학생 승/하차를 기록한다(source=MANUAL). */
    RideEventResponse record(AuthUser driver, RecordRideRequest req);

    /** 정정 — 원본은 그대로 두고 CORRECTION 기록을 새로 남긴다(정정 이력 추적). */
    RideEventResponse correct(AuthUser actor, Long eventId, CorrectionRequest req);

    /** 학생 본인의 하루치 기록. */
    List<RideEventResponse> getMyRecords(AuthUser student, LocalDate date);

    /** 학부모의 자녀(형제자매 포함) 하루치 기록. */
    List<RideEventResponse> getChildrenRecords(AuthUser parent, LocalDate date);

    /** 기사가 담당 버스의 하루치 기록. */
    List<RideEventResponse> getRosterRecords(AuthUser driver, Long busId, LocalDate date);

    /** 관리자가 소속(또는 지정) 학원의 기간 기록 — 정정 이력 포함. */
    List<RideEventResponse> getTenantRecords(AuthUser admin, Long tenantId, Long studentId,
                                             LocalDate from, LocalDate to);
}
