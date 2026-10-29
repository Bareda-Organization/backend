package src.backend.exception.command;

import java.time.OffsetDateTime;

import src.backend.bus.entity.Bus;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.exception.entity.EmergencyType;
import src.backend.manager.entity.Assignment;

/** 비상 신고 접수 한 건을 조립하는 데 필요한 확정된 입력 묶음(배치 · 버스 · 종류 · 요청 · 접수 시각). */
record EmergencyRaiseContext(Long academyId, Long runId, Bus bus, Assignment assignment, EmergencyType type,
        EmergencyRaiseRequest request, OffsetDateTime now) {
}
