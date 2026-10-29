package src.backend.admin.query;

import java.util.List;
import java.util.Map;

import src.backend.academy.entity.Academy;
import src.backend.account.entity.Account;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.entity.Manager;
import src.backend.run.entity.Run;

/**
 * 비상 알림 목록 조립에 쓰는 일괄 조회 결과 묶음 — 학원·매니저·확인자 계정·회차는 ID 기준, 배치 인력 연락처는 회차 ID 기준이다.
 */
record AdminEmergencyLookups(
        Map<Long, Academy> academiesById,
        Map<Long, Manager> managersById,
        Map<Long, Account> ackersById,
        Map<Long, Run> runsById,
        Map<Long, List<AssignedManagerContactView>> contactsByRunId) {
}
