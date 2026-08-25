package src.backend.global.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.persistence.AttributeConverter;

import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.entity.StaffStatus;
import src.backend.account.entity.AccountStatus;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequestStatus;
import src.backend.account.entity.VerificationPurpose;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.boarding.entity.ActorType;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.VerifyMethod;
import src.backend.exception.entity.ContactAttemptType;
import src.backend.exception.entity.ContactResult;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.entity.NoShowDecision;
import src.backend.notification.entity.DevicePlatform;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.entity.PushState;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.run.entity.RunStatus;
import src.backend.student.entity.Gender;
import src.backend.student.entity.LinkRequestStatus;

/**
 * enum 31종의 DB 값 집합이 {@code V1__init_schema.sql} 의 값 목록형 CHECK 39건과 정확히
 * 일치하는지 회귀 감시한다 — {@code ddl-auto: validate} 는 CHECK 를 전혀 보지 않으므로
 * (`IMPLEMENTATION_PLAN` 603행), 이 대조가 없으면 오늘 맞는 값이 내일 상수 하나만 고쳐도
 * 어디서도 실패하지 않는다.
 *
 * <p>{@code ChangeType} 처럼 CHECK 2개가 서로 다른 부분집합을 쓰는 경우는 두 CHECK 값의
 * 합집합과 비교한다 — Java 는 하나의 enum 만 두므로 "합쳐서 정확히 일치"가 우리가 확인할 수
 * 있는 전부이고, 부분집합 강제 자체는 여전히 DB CHECK 가 전담한다.
 */
class EnumCheckConstraintParityTest {

    private static final Pattern CHECK_PATTERN =
            Pattern.compile("CONSTRAINT\\s+(\\w+)\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)\\)");

    private static Map<String, Set<String>> checkValuesByConstraintName;

    @BeforeAll
    static void V1_스키마에서_값_목록형_CHECK_를_전부_파싱한다() throws IOException {
        checkValuesByConstraintName = parseValueListChecks();
    }

    private static Map<String, Set<String>> parseValueListChecks() throws IOException {
        String sql;
        try (InputStream in = EnumCheckConstraintParityTest.class
                .getResourceAsStream("/db/migration/V1__init_schema.sql")) {
            sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Set<String>> result = new HashMap<>();
        Matcher matcher = CHECK_PATTERN.matcher(sql);
        while (matcher.find()) {
            String constraintName = matcher.group(1);
            Set<String> values = Arrays.stream(matcher.group(2).split(","))
                    .map(token -> token.replaceAll("[\\s']", ""))
                    .filter(token -> !token.isBlank())
                    .collect(Collectors.toSet());
            result.put(constraintName, values);
        }
        return result;
    }

    private static <E extends Enum<E>> Set<String> dbValuesOf(Class<E> enumType, AttributeConverter<E, String> db) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(db::convertToDatabaseColumn)
                .collect(Collectors.toSet());
    }

    /** enum 이름 · 그 enum 의 DB 값 집합 · 대조할 CHECK 제약 이름들(2개 이상이면 합집합과 비교). */
    static Stream<Arguments> enumToCheckMapping() {
        return Stream.of(
                Arguments.of("Role", dbValuesOf(Role.class, new Role.Db()), Set.of("ck_account_role")),
                Arguments.of("Weekday", dbValuesOf(Weekday.class, new Weekday.Db()),
                        Set.of("ck_weekly_address_weekday", "ck_schedule_weekday", "ck_route_weekday")),
                Arguments.of("Direction", dbValuesOf(Direction.class, new Direction.Db()),
                        Set.of("ck_weekly_address_direction", "ck_schedule_direction",
                                "ck_route_direction", "ck_run_direction")),
                Arguments.of("ManagerRole", dbValuesOf(ManagerRole.class, new ManagerRole.Db()),
                        Set.of("ck_manager_role", "ck_assignment_role")),
                Arguments.of("ChangeType", dbValuesOf(ChangeType.class, new ChangeType.Db()),
                        Set.of("ck_run_stop_change", "ck_run_rider_change")),
                Arguments.of("AcademyStatus", dbValuesOf(AcademyStatus.class, new AcademyStatus.Db()),
                        Set.of("ck_academy_status")),
                Arguments.of("StaffStatus", dbValuesOf(StaffStatus.class, new StaffStatus.Db()),
                        Set.of("ck_academy_staff_status")),
                Arguments.of("AccountStatus", dbValuesOf(AccountStatus.class, new AccountStatus.Db()),
                        Set.of("ck_account_status")),
                Arguments.of("SignupRequestStatus", dbValuesOf(SignupRequestStatus.class, new SignupRequestStatus.Db()),
                        Set.of("ck_signup_request_status")),
                Arguments.of("ApproverType", dbValuesOf(ApproverType.class, new ApproverType.Db()),
                        Set.of("ck_signup_request_approver_type")),
                Arguments.of("VerificationPurpose", dbValuesOf(VerificationPurpose.class, new VerificationPurpose.Db()),
                        Set.of("ck_verification_code_purpose")),
                Arguments.of("Gender", dbValuesOf(Gender.class, new Gender.Db()), Set.of("ck_student_gender")),
                Arguments.of("LinkRequestStatus", dbValuesOf(LinkRequestStatus.class, new LinkRequestStatus.Db()),
                        Set.of("ck_link_request_status")),
                Arguments.of("RunStatus", dbValuesOf(RunStatus.class, new RunStatus.Db()), Set.of("ck_run_status")),
                Arguments.of("RouteVersionSource", dbValuesOf(RouteVersionSource.class, new RouteVersionSource.Db()),
                        Set.of("ck_route_version_source")),
                Arguments.of("RiderStatus", dbValuesOf(RiderStatus.class, new RiderStatus.Db()),
                        Set.of("ck_run_rider_status")),
                Arguments.of("VerifyMethod", dbValuesOf(VerifyMethod.class, new VerifyMethod.Db()),
                        Set.of("ck_rider_status_history_verify_method")),
                Arguments.of("ActorType", dbValuesOf(ActorType.class, new ActorType.Db()),
                        Set.of("ck_rider_status_history_actor_type")),
                Arguments.of("ChangeRequestSource", dbValuesOf(ChangeRequestSource.class, new ChangeRequestSource.Db()),
                        Set.of("ck_change_request_source")),
                Arguments.of("ChangeRequestType", dbValuesOf(ChangeRequestType.class, new ChangeRequestType.Db()),
                        Set.of("ck_change_request_type")),
                Arguments.of("ChangeRequestStatus", dbValuesOf(ChangeRequestStatus.class, new ChangeRequestStatus.Db()),
                        Set.of("ck_change_request_status")),
                Arguments.of("NoShowDecision", dbValuesOf(NoShowDecision.class, new NoShowDecision.Db()),
                        Set.of("ck_no_show_case_decision", "ck_no_show_contact_decision")),
                Arguments.of("ContactAttemptType", dbValuesOf(ContactAttemptType.class, new ContactAttemptType.Db()),
                        Set.of("ck_no_show_contact_attempt_type")),
                Arguments.of("ContactResult", dbValuesOf(ContactResult.class, new ContactResult.Db()),
                        Set.of("ck_no_show_contact_result")),
                Arguments.of("EmergencyType", dbValuesOf(EmergencyType.class, new EmergencyType.Db()),
                        Set.of("ck_emergency_alert_type")),
                Arguments.of("ExceptionReportType", dbValuesOf(ExceptionReportType.class, new ExceptionReportType.Db()),
                        Set.of("ck_exception_report_type")),
                Arguments.of("NotificationType", dbValuesOf(NotificationType.class, new NotificationType.Db()),
                        Set.of("ck_notification_log_type")),
                Arguments.of("PushState", dbValuesOf(PushState.class, new PushState.Db()),
                        Set.of("ck_notification_log_push_state")),
                Arguments.of("DevicePlatform", dbValuesOf(DevicePlatform.class, new DevicePlatform.Db()),
                        Set.of("ck_device_token_platform")),
                Arguments.of("AuditCategory", dbValuesOf(AuditCategory.class, new AuditCategory.Db()),
                        Set.of("ck_audit_log_category")),
                Arguments.of("AuditAction", dbValuesOf(AuditAction.class, new AuditAction.Db()),
                        Set.of("ck_audit_log_action"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumToCheckMapping")
    void enum_의_DB_값_집합이_CHECK_값_목록과_정확히_일치한다(
            String enumName, Set<String> enumDbValues, Set<String> constraintNames) {
        Set<String> checkValues = constraintNames.stream()
                .map(checkValuesByConstraintName::get)
                .peek(values -> assertThat(values)
                        .as(() -> "V1__init_schema.sql 에 이 이름의 값 목록형 CHECK 가 있어야 한다")
                        .isNotNull())
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        assertThat(enumDbValues)
                .as(() -> enumName + " 의 DB 값 집합이 " + constraintNames + " 의 값 목록과 정확히 일치해야 한다")
                .isEqualTo(checkValues);
    }

    /**
     * {@code enumToCheckMapping()} 은 제약 이름을 손으로 나열한 목록이라, 새 값 목록형 CHECK 가
     * V1 에 추가되고 이 목록에 줄을 안 넣으면 위 파라미터화 테스트는 그 존재조차 모른 채 통과한다.
     *
     * <p>정본은 스키마다 — {@code checkValuesByConstraintName}(V1 파싱 결과)에는 있는데
     * {@code enumToCheckMapping()} 이 참조하지 않는 제약 이름이 하나라도 있으면 실패시켜, "매핑에
     * 줄 추가를 잊었다"는 사실과 그 제약 이름을 실패 메시지에 그대로 드러낸다.
     */
    @Test
    void V1_의_값_목록형_CHECK_는_전부_enumToCheckMapping_에_매핑돼_있어야_한다() {
        Set<String> mappedConstraintNames = enumToCheckMapping()
                .flatMap(arguments -> ((Set<String>) arguments.get()[2]).stream())
                .collect(Collectors.toSet());

        Set<String> unmapped = new TreeSet<>(checkValuesByConstraintName.keySet());
        unmapped.removeAll(mappedConstraintNames);

        assertThat(unmapped)
                .as(() -> "V1__init_schema.sql 에 있는 값 목록형 CHECK 중 enumToCheckMapping() 이 "
                        + "참조하지 않는 것: " + unmapped)
                .isEmpty();
    }
}
