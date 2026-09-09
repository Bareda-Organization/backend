package src.backend.audit.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import src.backend.audit.dto.AuditLogItemResponse;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.response.PageResponse;

/**
 * {@code academy_id} 가 {@code null} 인 {@code data_access} 행이 목록 조회를 NPE 로 끊지 않는지 본다
 * (R1 재판정 — 지금의 {@code recordDataAccessRead} 호출부 4곳은 전부 {@code academyId} 를 구조적으로
 * 채우므로 실제로는 닿지 않지만, {@link AuditLogQueryService#academyNamesOf} 의 {@code Map.of()} 방어가
 * 없으면 이 전제가 깨지는 순간 그대로 500 이 된다).
 *
 * <p>{@code accountId} 필터로 조회를 이 시험이 심은 행 하나로 좁힌다 — 그래야
 * {@code academyNamesOf} 가 빈 목록을 받아 실제로 {@code Map.of()} 분기(수정 전 코드에서 NPE 가 나던
 * 그 자리)를 탄다. 시드의 유일한 {@code data_access} 행(actor_account_id=2)과 같은 계정을 쓰면 그
 * 행의 {@code academy_id=1} 이 섞여 {@code academyNamesOf} 가 일반 {@code HashMap} 을 돌려주는
 * 다른 분기로 새, 이 시험이 잡으려는 결함을 놓친다.
 */
@SpringBootTest
class AuditLogQueryServiceTest {

    /** 시드의 유일한 data_access 행(id=1)이 쓰는 계정 — 이 시험은 겹치지 않게 다른 계정을 쓴다. */
    private static final long 격리_계정 = 5L;

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private Clock clock;

    @Test
    void 학원_소속_없는_행이_있어도_목록_조회는_NPE_없이_학원명_null_로_응답한다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AuditLog log = auditLogRepository.save(AuditLog.forDataAccessRead(null, 격리_계정, "관리자심음", "student", 1L,
                Map.of("fields", "student_phone"), now));

        try {
            PageResponse<AuditLogItemResponse> result = auditLogQueryService.list(null, 격리_계정, null, null, 0, 20);

            assertThat(result.items())
                    .as("격리 계정으로 좁혔으니 방금 심은 행 1건만 실려야 한다")
                    .hasSize(1);
            assertThat(result.items().get(0).academyName())
                    .as("academy_id 가 null 인 행은 학원명도 null 이어야 한다 — 예외가 나면 이 단언까지 오지 못한다")
                    .isNull();
        } finally {
            auditLogRepository.deleteById(log.getId());
        }
    }
}
