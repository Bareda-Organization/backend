package src.backend.exception.query;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.exception.dto.RunEmergencyItemResponse;
import src.backend.exception.dto.RunEmergencyListResponse;
import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.LowerCaseFormatter;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;

/**
 * 발신한 비상 알림의 처리 상태 조회(API_SPEC §4.15 {@code GET /runs/{runId}/emergencies}) — 기사·동승자
 * 단말이 자신이 신고한 건이 확인·취소됐는지 확인하는 화면이다.
 *
 * <p>배치 확인은 {@link RunAssignmentAccess#assertAssignedDriverOrEscort} 하나로 끝낸다 — 존재하지
 * 않는 회차·다른 학원 회차·배치되지 않은 회차를 전부 {@code 403 FORBIDDEN} 하나로 묶는다(§1.11 "매니저
 * 앱 회차 자원의 403 FORBIDDEN(배치되지 않은 회차)"). {@code EmergencyCommandService#raise}·{@code
 * #cancel} 과 {@code ManagerRunAccess#requireAssignedRun}(review-f3-r4 #38~44, Ruling 259(b))이
 * 이미 같은 판단을 내려 두었다 — 배치 확인보다 먼저 회차 존재를 확인하면 "배치되지 않은 회차" 시나리오를
 * 결코 만나지 못하는 회귀가 났던 자리다. 이 서비스가 {@code RunRepository} 를 전혀 참조하지 않는 것은
 * 그 판단을 그대로 따른 결과다.
 *
 * <p>{@code acked_by_name} 은 {@code emergency_alert.acked_by} 가 담는 계정 id({@link
 * EmergencyAlert#ack} — {@code requester.accountId()})로 {@link AccountRepository} 를 다시 읽어
 * 채운다({@code emergency_alert} 에 이름 컬럼이 없다, {@code EmergencyStaffQueryService} 와 같은 이유).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmergencyRunQueryService {

    private final EmergencyAlertRepository emergencyAlertRepository;

    private final AccountRepository accountRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    public RunEmergencyListResponse list(AuthUser requester, Long runId) {
        runAssignmentAccess.assertAssignedDriverOrEscort(requester, runId);

        var alerts = emergencyAlertRepository.findAllByRunIdAndAcademyIdOrderByReceivedAtDesc(runId,
                requester.academyId());

        Map<Long, Account> ackersById = accountRepository
                .findAllByIdIn(alerts.stream().map(EmergencyAlert::getAckedBy).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, a -> a));

        var items = alerts.stream().map(alert -> toItem(alert, ackersById.get(alert.getAckedBy()))).toList();

        return new RunEmergencyListResponse(items);
    }

    private RunEmergencyItemResponse toItem(EmergencyAlert alert, Account acker) {
        return new RunEmergencyItemResponse(alert.getId(), LowerCaseFormatter.lower(alert.getType().name()),
                alert.getReceivedAt(), alert.cancelableUntil(), alert.isAcked(), alert.getAckedAt(),
                acker == null ? null : acker.getName(), alert.getCanceledAt());
    }
}
