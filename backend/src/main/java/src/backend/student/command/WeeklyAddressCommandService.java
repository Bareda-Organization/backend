package src.backend.student.command;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.domain.VerifiedAddressEntry;
import src.backend.student.dto.WeeklyAddressResponse;
import src.backend.student.dto.WeeklyAddressUpdateRequest;
import src.backend.student.entity.Student;

/**
 * 학부모의 요일별 주소 설정(P-05 · STU-05·06, API_SPEC §3.7 {@code PATCH}).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다.</b> 순서가 검증 → 저장이고 그 둘이
 * 다른 빈이라, 외부 호출(지오코딩)이 트랜잭션 밖에서 끝난다(§7 규칙 16). 한 트랜잭션으로 감싸면
 * 네이버 응답 시간만큼 커넥션과 행 잠금이 붙들리는데, 테스트는 스텁으로 돌아 그것을 보지 못한다.
 *
 * <p>자녀 접근 판정을 여기서 손으로 하지 않는다 — {@link GuardianChildAccess} 가 그 유일한 지점이고
 * (Ruling 117), 복제하면 다음에 추가되는 학부모 경로가 검사를 빠뜨린 채 태어난다.
 */
@Service
@RequiredArgsConstructor
public class WeeklyAddressCommandService {

    private final LinkedChildLookup linkedChildLookup;

    private final AddressVerification addressVerification;

    private final WeeklyAddressStore weeklyAddressStore;

    /**
     * 요일 × 방향 칸들의 주소를 검증하고 저장한다 — 검증에 실패하면 아무 행도 남지 않는다.
     *
     * <p>순서가 <b>접근 판정 → 검증 → 저장</b>인 것이 사양이다. 저장을 먼저 하고 검증하면
     * {@code 422} 를 던지면서 행이 남아, "저장 보류"(§3.7 · ERD {@code weekly_address.verified})가
     * 롤백 동작에 매달린 약속이 된다.
     */
    public WeeklyAddressResponse replace(AuthUser requester, Long studentId, WeeklyAddressUpdateRequest request) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        List<VerifiedAddressEntry> verified = addressVerification.verifyAll(request.entries());
        return WeeklyAddressResponse.from(
                weeklyAddressStore.apply(student.getId(), student.getAcademyId(), verified));
    }
}
