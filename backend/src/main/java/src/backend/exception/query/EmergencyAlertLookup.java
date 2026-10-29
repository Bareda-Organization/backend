package src.backend.exception.query;

import java.util.List;
import java.util.Map;

import src.backend.account.entity.Account;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.entity.Manager;
import src.backend.run.entity.Run;

/** 비상 알림 목록 조립에 필요한 매니저·확인자·회차·배치 연락처를 id 로 모아 둔 조회 결과. */
record EmergencyAlertLookup(
        Map<Long, Manager> managersById,
        Map<Long, Account> ackersById,
        Map<Long, Run> runsById,
        Map<Long, List<AssignedManagerContactView>> contactsByRunId) {
}
