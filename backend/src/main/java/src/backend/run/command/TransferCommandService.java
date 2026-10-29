package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.dto.TransferRequest;
import src.backend.run.dto.TransferResponse;
import src.backend.run.dto.TransferResponse.FromImpact;
import src.backend.run.dto.TransferResponse.ToImpact;
import src.backend.run.dto.TransferResponse.TransferImpact;
import src.backend.run.entity.RunTransfer;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.command.TransferPreconditions.Runs;
import src.backend.run.command.TransferPreconditions.TargetCapacity;
import src.backend.student.service.AddressVerification;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopRepository;

/**
 * 관계자의 버스 간 이동(RTE-07, API_SPEC §5.8, Ruling 256) — 학생 1명을 출발 회차 명단에서 빼
 * 도착 회차 명단에 넣는 신청을 접수한다.
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다</b> — {@code address} 경로면
 * {@link AddressVerification} 이 외부 지오코딩을 호출한다({@link ForcedAdditionCommandService} 와
 * 같은 근거, §7 규칙 16). "구간 판정 → 명단 소속 판정 → 정원 판정" 은 여기서 트랜잭션 밖에 두고,
 * 저장(+ {@code stop_id} 확정)만 {@link TransferStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>§5.8 은 {@link ForcedAdditionCommandService} 와 같은 <b>① 구간 전용</b>이되, 대상 회차가
 * 둘이라 <b>둘 중 하나라도</b> ①구간을 벗어나면 막는다(사양 본문 "둘 중 한 회차라도 ② 구간이면").
 */
@Service
@RequiredArgsConstructor
public class TransferCommandService {

    private final TransferPreconditions preconditions;

    private final StopRepository stopRepository;

    private final AddressVerification addressVerification;

    private final TransferStore transferStore;

    private final Clock clock;

    /**
     * ①구간 전용 버스 간 이동(§5.8) — 두 회차 모두 ①구간·정원·주소를 확인한 뒤 대기 상태로만
     * 등록한다.
     *
     * <p>"학생 소속 판정 → 두 회차 구간 판정 → 중복 대기 → 출발 명단 소속 → 정원 판정 → 주소 확인" 이
     * 트랜잭션 밖에서 이 순서대로 실행돼야 한다. 순서가 바뀌면 같은 요청에 다른 오류 코드가 나간다.
     */
    public TransferResponse transfer(AuthUser requester, Long studentId, TransferRequest request) {
        Student student = preconditions.findStudent(requester, studentId);
        Runs runs = preconditions.findRuns(requester, request);
        preconditions.assertWindowsOpen(runs);
        preconditions.assertNotStaged(requester, student);
        ProjectedRoster fromRoster = preconditions.readFromRoster(runs.from(), student);
        assertStopOrAddressExclusive(request);
        TargetCapacity target = preconditions.checkTargetCapacity(requester, runs.to(), student);
        GeocodedPoint point = verifyDestination(requester, request);

        RunTransfer transfer = transferStore.stage(runs.from(), runs.to(), student, request, point,
                requester.accountId(), OffsetDateTime.now(clock));
        return TransferResponse.of(transfer, impactOf(fromRoster, target));
    }

    /** 승하차지 경로면 학원 안 승하차지 존재만 확인하고(좌표 없음), 주소 경로면 지오코딩한 좌표를 돌려준다. */
    private GeocodedPoint verifyDestination(AuthUser requester, TransferRequest request) {
        if (request.stopId() == null) {
            return addressVerification.verifySingle(request.address());
        }
        // 도착 회차 노선의 기존 승하차지가 아니라 <b>학원 안 전체</b>에서 찾는다 — §5.8 은 "도착
        // 회차 노선의 기존 승하차지" 라고 적었지만, 강제 추가(§5.7)도 노선 소속이 아니라 학원
        // 승하차지 전체를 대상으로 매칭한다(STU-05) — 노선 소속으로 좁히면 그 정류장이 아직
        // 이 노선에 배정되지 않은 경우까지 막혀, 강제 추가와 다른 기준이 된다.
        stopRepository.findAllByAcademyIdAndIdIn(requester.academyId(), List.of(request.stopId())).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOP_NOT_FOUND));
        return null;
    }

    private static TransferImpact impactOf(ProjectedRoster fromRoster, TargetCapacity target) {
        return new TransferImpact(
                new FromImpact(fromRoster.size(), fromRoster.size() - 1),
                new ToImpact(target.currentCount(), target.currentCount() + 1, target.capacity()));
    }

    private static void assertStopOrAddressExclusive(TransferRequest request) {
        boolean hasStop = request.stopId() != null;
        boolean hasAddress = request.address() != null && !request.address().isBlank();
        if (hasStop == hasAddress) {
            // 둘 다 없거나 둘 다 있으면 어느 쪽으로 처리할지 정할 수 없다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
