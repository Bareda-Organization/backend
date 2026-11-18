package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 횡단 계층 {@code global} 이 도메인 모듈을 import 하는 줄을 기준선과 같게 묶어 둔다(Ruling 938 I) — 모듈끼리의 순환은
 * {@link ModuleCycleBaselineTest} 가 얼리지만 그 검사는 {@code global} 을 보지 않아, {@code global} 에서 모듈로 새 의존이
 * 생겨도 초록이었다. 기준선 밖의 새 import 는 실패하고, 사라진 import 가 기준선에 남아 있어도 실패한다(목록은 줄어드는 쪽으로만 움직인다).
 */
class GlobalModuleImportBaselineTest {

    /** {@code global} 안에서 다른 {@code global} 하위로 가는 import 는 모듈 의존이 아니다. */
    private static boolean importsDomainModule(String imported) {
        return !imported.startsWith("src.backend.global.");
    }

    /** 항목마다 위 주석으로 이유를 둔다 — 새 항목은 이유와 함께 더하고, 코드에서 사라진 항목은 지운다. */
    private static final Set<String> BASELINE = Set.of(
        // 개발용 초기화가 모듈 상태(위치 · 미리보기 · 회차 생성)를 비운다 — 운영 경로가 아닌 개발 전용 서비스다
        "global/dev/DevResetService.java -> src.backend.location.infrastructure.RunPositionStore",
        "global/dev/DevResetService.java -> src.backend.request.preview.spec.ApprovalPreviewCache",
        "global/dev/DevResetService.java -> src.backend.schedule.command.RunGenerationService",
        // 지도 장애 예외를 응답으로 옮기는 전역 처리기가 그 예외 타입을 안다
        "global/error/GlobalExceptionHandler.java -> src.backend.routing.map.spec.MapRouteUnavailableException",
        // 요청 값 변환이 모듈의 enum 값 이름을 읽는다 — 값 이름의 정의처가 각 모듈 enum 이다
        "global/request/ApiValues.java -> src.backend.exception.entity.ExceptionReportType",
        "global/request/ApiValues.java -> src.backend.notification.entity.NotificationType",
        "global/request/ApiValues.java -> src.backend.request.entity.ChangeRequestStatus",
        // 보존 정리 배치가 모듈별 만료 데이터를 직접 지운다 — 삭제 대상을 아는 쪽이 각 모듈 저장소라 이 배치가 모듈을 안다
        "global/retention/RetentionCleanupScheduler.java -> src.backend.account.repository.RefreshTokenRepository",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.account.repository.VerificationCodeRepository",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.audit.entity.AuditCategory",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.audit.repository.AuditLogRepository",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.location.infrastructure.RunPositionPartitionManager",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.notification.repository.NotificationLogRepository",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.observability.metrics.RefreshTokenRowsMetrics",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.run.repository.DelayNoticeRepository",
        "global/retention/RetentionCleanupScheduler.java -> src.backend.student.repository.LinkCodeRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.account.repository.AccountRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.account.repository.RefreshTokenRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.audit.entity.AuditAction",
        "global/retention/StudentAnonymizationService.java -> src.backend.audit.entity.AuditLog",
        "global/retention/StudentAnonymizationService.java -> src.backend.audit.service.AuditRecorder",
        "global/retention/StudentAnonymizationService.java -> src.backend.notification.repository.DeviceTokenRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.request.repository.ChangeRequestRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.entity.Student",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.photo.spec.PhotoStorage",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.repository.GuardianStudentRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.repository.LinkCodeRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.repository.StudentRepository",
        "global/retention/StudentAnonymizationService.java -> src.backend.student.repository.WeeklyAddressRepository",
        "global/retention/WithdrawnStudentAnonymizer.java -> src.backend.student.repository.StudentRepository",
        // 방송 게이트웨이가 발행 지표 홀더(observability.metrics)를 직접 갱신한다
        "global/websocket/LocalBroadcastDelivery.java -> src.backend.observability.metrics.WebSocketPublishMetrics",
        "global/websocket/WebSocketBroadcastGateway.java -> src.backend.observability.metrics.WebSocketPublishMetrics"
    );

    @Test
    @DisplayName("global 이 도메인 모듈을 import 하는 줄은 기준선과 같다")
    void global_의_모듈_import_는_기준선과_같다() throws IOException {
        TreeSet<String> actual = JavaSourceScan.violations(JavaSourceScan.MAIN,
                path -> path.startsWith("global/"), GlobalModuleImportBaselineTest::importsDomainModule);

        JavaSourceScan.assertMatchesBaseline(actual, BASELINE, "global → 도메인 모듈 import");
    }

    @Test
    @DisplayName("모의 global 클래스가 도메인 모듈을 import 하면 잡히고, global 끼리의 import 는 안 잡힌다")
    void 모의_소스의_모듈_import_를_잡는다() throws IOException {
        TreeSet<String> found = JavaSourceScan.violations(JavaSourceScan.FIXTURES.resolve("global-import"),
                path -> path.startsWith("global/"), GlobalModuleImportBaselineTest::importsDomainModule);

        assertThat(found).containsExactly("global/Leaky.java -> src.backend.student.repository.StudentRepository");
    }
}
