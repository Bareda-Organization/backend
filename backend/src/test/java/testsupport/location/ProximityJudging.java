package testsupport.location;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.location.proximity.ProximityNotificationService;

/**
 * 근접·출발 판정을 스케줄러 없이 회차 1건에 돌리는 시험 보조 — 운영에서는 스케줄러가 회차 묶음당 한 번 읽어 넘기는 위치를 여기서는
 * 회차 1건만 읽는다(R46-LATERBE L5). 위치가 없는 회차는 스케줄러처럼 건너뛰고, 판정이 던진 실패는 시험을 바로 실패시킨다(조용히 삼키면
 * 판정이 안 돈 것과 구별되지 않는다).
 */
public final class ProximityJudging {

    private ProximityJudging() {
    }

    public static void judge(ProximityNotificationService service, RunPositionStore store, Long runId, Long academyId) {
        RunPositionRedisValue position = store.find(runId).orElse(null);
        if (position == null) {
            return;
        }
        service.judgeRun(runId, academyId, position, (judgment, e) -> {
            throw new AssertionError("회차 " + runId + " " + judgment + " 판정이 실패했다", e);
        });
    }
}
