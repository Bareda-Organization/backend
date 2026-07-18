package src.backend.sos.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.sos.dto.SosEventResponse;
import src.backend.sos.dto.SosTriggerRequest;

public interface SosService {

    /** 학생 발신 — OPEN 생성 + 즉시 알림(학부모+관리자). */
    SosEventResponse trigger(AuthUser student, SosTriggerRequest req);

    /** 관리자 확인(OPEN → ACKNOWLEDGED). */
    SosEventResponse acknowledge(AuthUser admin, Long id);

    /** 상황 종료(ACKNOWLEDGED → RESOLVED). */
    SosEventResponse resolve(AuthUser admin, Long id);

    /** 학생 본인 SOS 이력. */
    List<SosEventResponse> getMyEvents(AuthUser student);

    /** 학부모: 자녀(형제자매 포함) SOS 이력. */
    List<SosEventResponse> getChildrenEvents(AuthUser parent);

    /** 관리자: 학원 SOS 이력. */
    List<SosEventResponse> getTenantEvents(AuthUser admin, Long tenantId);

    /** 3분간 미확인(OPEN) 상태인 이벤트를 플랫폼관리자에게 에스컬레이션 — 스케줄러가 주기 호출. */
    void escalateOverdue();
}
