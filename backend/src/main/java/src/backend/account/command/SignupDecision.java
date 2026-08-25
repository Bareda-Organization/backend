package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.SignupDecisionResponse;
import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 두 승인 축(API_SPEC §5.2 관계자 · §6.5 메인 관리자)이 <b>공유하는</b> 마감 절차.
 *
 * <p>공유하는 것은 넷이다 — ①이미 처리된 건 거부 ②{@code accept=false} 인데 사유가 없으면 거부
 * ③요청 행에 처리자·일시 적재 ④계정 상태 전이. 복제하면 규칙이 한 번 바뀔 때 한쪽만 따라가고,
 * 그 순간 <b>양쪽 테스트는 계속 통과한다</b> — 각자 자기 사본을 보기 때문이다.
 *
 * <p>축마다 <b>갈리는 것</b>은 여기 없다. 대상 역할 판정·학원 격리·레코드 연결(AUTH-11)·정원 판정은
 * 호출부가 하고, 수락일 때만 필요한 부수효과는 {@code onAccept} 로 받는다 — 그것까지 이 클래스가
 * 알면 두 축의 인가 경계가 한 곳에 뭉개져 어느 쪽 규칙인지 읽을 수 없게 된다.
 */
@Component
@RequiredArgsConstructor
public class SignupDecision {

    private final AccountRepository accountRepository;

    private final Clock clock;

    /**
     * 대기 중인 요청을 수락·거절로 마감한다.
     *
     * <p>순서가 이 메서드의 내용이다. ①{@code assertPending} 을 <b>맨 먼저</b> 부른다 — 뒤로 미루면
     * 이미 처리된 건이 {@code onAccept} 의 실패(연결 누락·정원 초과)로 먼저 답해 원인이 뒤바뀐다.
     * ②거절은 사유 검증을 <b>어떤 상태 변경보다 먼저</b> 한다 — 뒤에 두면 422 를 돌려주면서 계정만
     * 거절 상태로 남는다. ③수락은 {@code onAccept} 를 계정 전이보다 먼저 부른다 — 정원 초과로 막힐
     * 승인이 계정을 활성화해 두는 것을 막는다.
     *
     * @param request  마감할 요청. 대기 상태가 아니면 {@code 409 APPROVAL_ALREADY_DECIDED}
     * @param decision 수락 여부와 거절 사유
     * @param deciderId 처리한 계정 — 승인 이력의 "누가" 에 해당한다
     * @param onAccept 수락일 때만 실행하는 축별 부수효과(레코드 연결 · 관계자 행 생성)
     */
    public SignupDecisionResponse close(SignupRequest request, Decision decision, Long deciderId,
            Consumer<Account> onAccept) {
        request.assertPending();
        Account account = accountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        OffsetDateTime decidedAt = OffsetDateTime.now(clock);

        if (decision.accept()) {
            onAccept.accept(account);
            account.approveSignup();
            request.accept(deciderId, decidedAt);
        } else {
            String reason = requireRejectReason(decision.rejectReason());
            account.rejectSignup();
            request.reject(deciderId, decidedAt, reason);
        }
        return SignupDecisionResponse.of(account.getStatus(), decidedAt);
    }

    /**
     * 거절 사유는 {@code accept=false} 의 필수 항목이다(§5.2·§6.5) — 없으면 {@code 422 VALIDATION_FAILED}.
     *
     * <p>Bean Validation 으로 표현하지 않는 이유는 필수 여부가 같은 본문의 다른 필드({@code accept})에
     * 달려 있기 때문이다. 사유가 비면 §1.4 가 요구하는 "대기 화면에 거절 사유 노출" 이 빈 문자열이 된다.
     */
    private String requireRejectReason(String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return rejectReason;
    }
}
