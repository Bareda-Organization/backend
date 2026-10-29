package src.backend.boarding.query;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import src.backend.audit.service.AuditRecorder;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.dto.ManagerRosterResponse.RosterStudent;
import src.backend.boarding.dto.ManagerRosterResponse.StopGroup;
import src.backend.boarding.dto.StaffRosterItemResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 회차 명단 조회 — 매니저 앱(§4.2)과 관계자 웹(§5.4)이 <b>같은 원본</b>({@code run_rider} ·
 * {@code run_stop} · {@code Student})을 서로 다른 응답 모양으로 읽는다(RST-01~04·A-04).
 *
 * <p>두 메서드가 대칭으로 갈리는 지점이 <b>둘</b>이다 — {@code guardian_phone}
 * 마스킹 여부({@link GuardianPhoneMasker})와 {@code absent} 학생의 노출 여부(매니저 앱은 행 제외
 * · 관계자 웹은 빨강 표시로 존치). 한쪽만 고치면 어느 화면이 깨졌는지 코드만 보고는 알 수 없어
 * 이 클래스 하나에 함께 둔다. 응답 조립은 {@link ManagerRosterAssembler} · {@link StaffRosterAssembler},
 * 저장소 조회는 {@link RosterDataReader} 가 맡고 이 클래스는 읽기 트랜잭션 경계와 감사 기록 순서를 쥔다.
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — 감사 기록({@code AuditRecorder}, {@code REQUIRES_NEW})이 읽기
 * 트랜잭션 안에서 돌면 요청 하나가 DB 연결을 둘 쥐고, 동시 요청이 풀 크기에 닿으면 서로의 두 번째 연결을 기다려
 * 풀리지 않는다. 조회는 {@link #readOnly} 읽기 트랜잭션 하나(연결 1회 획득)로 끝내 연결을 돌려준 뒤 감사를
 * 기록한다(DA-6) — 조회가 예외로 끝나면 감사 호출에 닿지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RosterQueryService {

    private final RunRepository runRepository;

    private final AuditRecorder auditRecorder;

    private final ManagerRosterAssembler managerRosterAssembler;

    private final StaffRosterAssembler staffRosterAssembler;

    private final PlatformTransactionManager transactionManager;

    /**
     * 매니저 앱의 승하차지별 명단(§4.2) — 확정 전(idle) 회차는 {@code 409 RUN_NOT_CONFIRMED}(명단이
     * 아직 채워지지 않아 빈 배열과 "확정됐는데 비었다" 가 구별되지 않기 때문, {@code ErrorCode} 참고).
     *
     * <p>회차 접근 판정은 {@link ManagerRunAccess#requireAssignedRun} 이 먼저 한다(회차 없음·타 학원·
     * 미배치 세 경우 모두 {@code 403 FORBIDDEN} — §1.11, Ruling 259(b)) — 이 상태 검사는 그 다음이다.
     * 접근이 아니라 자원 상태를 묻는 질문이라 별도 계층에 둔다.
     */
    public ManagerRosterResponse managerRoster(AuthUser requester, Long runId) {
        ManagerRosterResponse response = readOnly(() -> managerRosterAssembler.assemble(requester, runId));
        recordManagerRosterAudit(requester, runId, response.stops());
        return response;
    }

    /**
     * 매니저 앱 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3) — {@code photo_url}·
     * {@code note}·{@code address}(정차지, 학생 승하차지 원문 위치)가 L3 다. {@code guardian_phone} 은
     * 이 응답에서 <b>마스킹된 값</b>이라 감사 대상에서 뺀다(§6.3 L2 마스킹 — "원본" 공개가 아니면
     * L3 의 감사 요건이 붙지 않는다).
     *
     * <p>{@code absent} 학생은 이미 응답({@code stops}) 조립 단계에서 행이 빠졌으므로, 그 학생의
     * L3 필드는 애초에 응답에 실리지 않는다 — {@code student_ids} 를 원본 {@code riders} 가 아니라
     * 조립된 {@code stops} 에서 다시 뽑는 이유다. 응답에 학생이 하나도 없으면(전원 결석·미배정)
     * L3 값이 실제로 실리지 않았으므로 행을 남기지 않는다({@code StudentQueryService.detail} 과
     * 같은 근거).
     */
    private void recordManagerRosterAudit(AuthUser requester, Long runId, List<StopGroup> stops) {
        List<String> studentIds = stops.stream()
                .flatMap(stopGroup -> stopGroup.students().stream())
                .map(RosterStudent::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(requester.academyId(), requester.accountId(), "run_roster", runId,
                Map.of("student_ids", studentIds, "fields", List.of("photo_url", "note", "address")));
    }

    /**
     * 관계자 웹의 호차별 일일 명단(§5.4) — 매니저 앱과 달리 확정 전(idle) 회차도 조회할 수 있고
     * (사양 원문 "진입 차단은 매니저 앱 전용"), 배치되지 않았다는 이유로 막지 않는다 — 학원 관계자는
     * 그 학원의 회차 전체를 볼 권한을 이미 {@code STUDENT_READ_SENSITIVE} 로 가졌다.
     *
     * <p>존재 판정과 학원 범위 판정을 분리한다(API_SPEC §1.5, Ruling 239) —
     * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
     * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다. 조회
     * 조건에 학원 id 를 섞으면(옛 {@code findByIdAndAcademyId}) 두 사유가 같은 404 로 뭉개진다.
     */
    public List<StaffRosterItemResponse> staffRoster(AuthUser requester, Long runId) {
        StaffRoster roster = readOnly(() -> assembleStaffRoster(requester, runId));
        recordStaffRosterAudit(requester, roster.run(), roster.items());
        return roster.items();
    }

    /** 관계자 명단 조립 — 읽기 트랜잭션 하나 안에서 돈다. 감사에 쓸 회차를 함께 돌려준다. */
    private StaffRoster assembleStaffRoster(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());
        List<StaffRosterItemResponse> items = run.getStatus() == RunStatus.IDLE
                ? staffRosterAssembler.plannedItems(run)
                : staffRosterAssembler.confirmedItems(run);
        return new StaffRoster(run, items);
    }

    private record StaffRoster(Run run, List<StaffRosterItemResponse> items) {
    }

    /** 조회 전체를 읽기 트랜잭션 하나로 — 저장소 호출마다 연결을 따로 얻지 않는다(DA-6). */
    private <T> T readOnly(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    /**
     * 관계자 웹 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3) — {@code guardian_phone}
     * 이 이 응답에서는 <b>원본</b>이다(§5.4 마스킹 대상 밖, {@link StaffRosterItemResponse} 자바독) —
     * §6.3 L3 행이 명명한 "보호자 연락처 원본" 이 바로 이 값이라 감사 대상이다. {@code note} 도 L3.
     * {@code photo_url} 은 이 응답에 부재(§5.4 필드 목록에 없음)라 감사 대상에 넣지 않는다.
     *
     * <p>매니저 앱과 달리 {@code absent} 학생도 행으로 남으므로(자바독의 "행 제외 대상 아님") 원본
     * {@code riders} 를 다시 훑지 않고 <b>조립된 응답</b>에서 바로 뽑아도 결과가 같다 — 그래도
     * "응답에 실제로 실린 값" 원칙을 지키려 응답 리스트를 그대로 쓴다.
     */
    private void recordStaffRosterAudit(AuthUser requester, Run run, List<StaffRosterItemResponse> items) {
        List<String> studentIds = items.stream()
                .map(StaffRosterItemResponse::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(run.getAcademyId(), requester.accountId(), "run_roster", run.getId(),
                Map.of("student_ids", studentIds, "fields", List.of("guardian_phone", "note")));
    }
}
