package src.backend.request.command;

import java.util.Locale;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.request.entity.BoardingIntent;

/** 탑승 의사 토글 응답의 {@code rider_status}·변경 한도 잔여를 만드는 읽기 전용 도우미(구간별 처리 단계가 함께 쓴다). */
@Component
@RequiredArgsConstructor
class BoardingIntentStatusReader {

    private final RunRiderRepository runRiderRepository;

    /**
     * 응답의 {@code rider_status} — 실제 {@code run_rider} 행이 있으면 그 상태를, 아직 없으면(①구간은
     * 확정 전이라 항상 이 경우다) {@code riding} 으로부터 합성한다. API_SPEC §3.6 이 명시하는 규칙은
     * "{@code applied} + {@code riding=false} → {@code absent}" 하나뿐이라, 나머지 조합은 이 합성으로
     * 일관되게 채운다.
     */
    String riderStatusOf(Long runId, Long studentId, boolean riding) {
        return runRiderRepository.findByRunIdAndStudentId(runId, studentId)
                .map(r -> statusNameOf(r.getStatus()))
                .orElse(riding ? statusNameOf(RiderStatus.WAITING) : statusNameOf(RiderStatus.ABSENT));
    }

    /** 탑승자 상태를 응답 계약의 소문자 문자열로 옮긴다. */
    static String statusNameOf(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** ②구간 변경 한도 잔여 — {@link BoardingIntent#hasChangeQuota} 를 응답 계약의 정수 형태로 옮긴다. */
    static int quotaLeftOf(BoardingIntent intent) {
        return intent.hasChangeQuota() ? 1 : 0;
    }
}
