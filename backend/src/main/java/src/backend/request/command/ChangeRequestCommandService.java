package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.ChangeRequestCreateRequest;
import src.backend.request.dto.ChangeRequestCreateResponse;
import src.backend.request.entity.ChangeRequestType;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.command.AddressVerification;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 학부모의 일일 변경 신청(P-06, API_SPEC §3.8).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다.</b> {@code type=relocate} 는 주소
 * 검증(외부 지오코딩 호출)을 거치는데, 그 호출을 트랜잭션 안에 넣으면 공급자가 느린 만큼 DB 커넥션을
 * 붙든 채 대기한다({@code WeeklyAddressCommandService} 와 같은 근거, §7 규칙 16). 그래서 "접근 판정 →
 * 회차 조회 → 구간 판정 → 주소 검증" 은 여기서 트랜잭션 밖에 두고, 저장·한도 소비·알림 발행만
 * {@link ChangeRequestStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>③구간 판정은 {@code type} 을 가리지 않는다(목표 9) — {@code cancel} 도 여기서 막힌다. ③구간의
 * 탑승 취소는 이 엔드포인트가 아니라 별도 탑승 의사 토글 엔드포인트(T2 소유)를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ChangeRequestCommandService {

    private final LinkedChildLookup linkedChildLookup;

    private final RunRepository runRepository;

    private final AddressVerification addressVerification;

    private final ChangeRequestStore changeRequestStore;

    private final Clock clock;

    public ChangeRequestCreateResponse submit(AuthUser requester, Long studentId, ChangeRequestCreateRequest request) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        ChangeRequestType type = parseType(request.type());
        Run run = runRepository.findByIdAndAcademyId(request.runId(), student.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, now);
        if (window == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }

        if (type == ChangeRequestType.RELOCATE) {
            return submitRelocate(student, run, window, requester.accountId(), request, now);
        }
        return ChangeRequestCreateResponse.from(
                changeRequestStore.submitCancel(student, run, window, requester.accountId(), request.reason(), now));
    }

    private ChangeRequestCreateResponse submitRelocate(Student student, Run run, ChangeWindow window,
            Long requestedBy, ChangeRequestCreateRequest request, OffsetDateTime now) {
        if (request.newAddress() == null || request.newAddress().isBlank()) {
            // new_address 는 type=relocate 일 때만 필수인 조건부 필드다(§3.8) — DTO 단
            // @NotBlank 로는 이 조건을 표현할 수 없어 여기서 판정한다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        GeocodedPoint point = addressVerification.verifySingle(request.newAddress());
        return ChangeRequestCreateResponse.from(changeRequestStore.submitRelocate(student, run, window, requestedBy,
                request.newAddress(), point, request.reason(), now));
    }

    /** 사양에 없는 type 값은 조용히 무시하지 않고 {@code 422} 로 거부한다(§9 enum 사전). */
    private static ChangeRequestType parseType(String raw) {
        try {
            return ChangeRequestType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
