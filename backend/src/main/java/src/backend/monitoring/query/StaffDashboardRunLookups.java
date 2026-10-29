package src.backend.monitoring.query;

import java.util.List;
import java.util.Map;

import src.backend.boarding.dto.StaffRunRiderAggregateView;
import src.backend.exception.dto.StaffNoShowCaseView;
import src.backend.exception.entity.NoShowContact;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffDashboardResponse;
import src.backend.routing.entity.RunStop;

/**
 * 회차 하나를 응답 한 행으로 만드는 데 필요한, 회차 수와 무관하게 미리 한 번씩 읽어 둔 조회 결과 묶음(BR-101) —
 * {@code stopsByRun} 은 지연 분 계산용이라 {@code moving} 회차만 담긴다.
 */
record StaffDashboardRunLookups(Map<Long, String> busNos, Map<Long, List<StaffRunRiderAggregateView>> riderAggByRun,
        Map<Long, List<StaffAssignmentAckView>> ackViewsByRun,
        Map<Long, List<StaffNoShowCaseView>> noShowViewsByRun, Map<Long, List<NoShowContact>> contactsByCaseId,
        Map<Long, List<RunStop>> stopsByRun, Map<Long, StaffDashboardResponse.LastDelayNotice> lastDelayNoticeByRun) {
}
