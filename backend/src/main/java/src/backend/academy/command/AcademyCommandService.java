package src.backend.academy.command;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyRegisterRequest;
import src.backend.academy.dto.AcademyRegisterResponse;
import src.backend.academy.dto.AcademyUpdateRequest;
import src.backend.academy.repository.AcademyRepository;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.service.AddressVerification;

/**
 * 학원 등록·수정·비활성화(ACAD-02·04, API_SPEC §6.2·§6.3).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다.</b> 순서가 주소 검증(지오코딩) → 저장이고
 * 그 둘이 다른 빈({@link AddressVerification} · {@link AcademyStore})이라, 외부 호출이 트랜잭션 밖에서
 * 끝난다(§7 규칙 16 · {@code Ruling 374}). 학생 주소({@code WeeklyAddressCommandService})와 같은 구조다.
 */
@Service
@RequiredArgsConstructor
public class AcademyCommandService {

    private final AcademyRepository academyRepository;

    private final AddressVerification addressVerification;

    private final AcademyStore academyStore;

    /**
     * 학원을 등록한다(API_SPEC §6.2) — 코드는 서버가 만들고 응답으로 돌려준다.
     *
     * <p>학원명 + 지역이 겹쳐도 <b>저장을 막지 않는다</b>. 분원이 실제로 존재할 수 있어 중복을 에러로
     * 다루면 정당한 등록이 통째로 막히기 때문이며, 대신 경고를 실어 관리자가 알아채게 한다.
     *
     * <p>{@code address} 가 있으면 좌표로 옮겨 함께 저장한다 — 옮기지 못하면 {@code 422} 로 저장을 보류한다.
     * 좌표가 없는 학원은 회차 확정이 전부 실패한다.
     */
    public AcademyRegisterResponse register(AcademyRegisterRequest request) {
        return academyStore.register(request, coordinatesOf(request.address()));
    }

    /**
     * 학원 정보를 고치거나 비활성화한다(API_SPEC §6.3 PATCH).
     *
     * <p>비활성화는 신규 가입만 막고 <b>기존 사용자의 로그인은 유지</b>한다(ACAD-04) — 이 메서드가 계정
     * 상태를 함께 건드리지 않는 것이 그 요건을 지키는 방식이다. 운행 중 기사가 로그아웃되는 것을 막는다.
     *
     * <p>{@code address} 가 바뀌면 좌표를 다시 구한다. <b>같은 주소를 다시 보낸 수정은 지오코딩하지 않는다</b>
     * — 관리자 화면이 폼 전체를 보내도 이름만 고친 수정이 공급자 장애·주소 표기 변화로 막히면 안 된다.
     *
     * @return 수정된 학원의 식별자 — 응답 조립은 조회 쪽이 맡는다(§1.9 "변경 후 자원 상태를 반환")
     */
    public Long update(Long academyId, AcademyUpdateRequest request) {
        GeocodedPoint point = addressChanged(academyId, request.address()) ? coordinatesOf(request.address()) : null;
        return academyStore.update(academyId, request, point);
    }

    /** 주소를 보냈고 저장된 값·좌표와 다를 때만 참 — 없는 학원은 저장 쪽이 404 로 답하게 둔다. */
    private boolean addressChanged(Long academyId, String address) {
        if (address == null) {
            return false;
        }
        return academyRepository.findById(academyId)
                .map(current -> !address.equals(current.getAddress()) || !current.hasCoordinates())
                .orElse(false);
    }

    /** 빈 주소는 좌표 없음({@code null}), 그 밖에는 검증을 통과한 좌표 — 못 옮기면 {@code 422}. */
    private GeocodedPoint coordinatesOf(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return addressVerification.verifySingle(address.trim());
    }
}
