package src.backend.monitoring.query;

import java.util.List;
import java.util.Map;

/** 대시보드가 읽는 학원 범위(§6.18) — {@code academy_id} 를 주면 그 학원 하나, 아니면 전 학원. 이름은 응답의 학원 이름 칸에 쓴다. */
record DashboardScope(List<Long> academyIds, Map<Long, String> academyNames) {
}
