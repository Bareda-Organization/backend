package src.backend.academy.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyRegisterRequest;
import src.backend.academy.dto.AcademyRegisterResponse;
import src.backend.academy.dto.AcademyUpdateRequest;
import src.backend.academy.dto.AcademyWarning;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyProfile;
import src.backend.academy.entity.AcademySetting;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** 학원 등록·수정·비활성화(ACAD-02·04, API_SPEC §6.2·§6.3). */
@Service
@RequiredArgsConstructor
@Transactional
public class AcademyCommandService {

    private final AcademyRepository academyRepository;

    private final AcademySettingRepository academySettingRepository;

    private final AcademyCodeGenerator academyCodeGenerator;

    /**
     * 학원을 등록한다(API_SPEC §6.2) — 코드는 서버가 만들고 응답으로 돌려준다.
     *
     * <p>학원명 + 지역이 겹쳐도 <b>저장을 막지 않는다</b>. 분원이 실제로 존재할 수 있어 중복을 에러로
     * 다루면 정당한 등록이 통째로 막히기 때문이며, 대신 경고를 실어 관리자가 알아채게 한다.
     *
     * <p>학원별 설정({@link AcademySetting})도 같은 트랜잭션에서 기본값으로 함께 만든다(Phase 11 목표 4)
     * — {@code AcademySetting} 자신의 팩토리 javadoc이 "학원 등록과 같은 트랜잭션에서" 생성을 전제로
     * 두고 있는데, 이 서비스가 그동안 만들지 않고 있었다(Phase 11 착수 시 발견한 간극) — 만들지 않으면
     * 첫 미승차가 나기 전까지는 {@code GET /staff/academy-settings} 가 매번 자가 치유 경로로 즉석 생성해야
     * 하고, 그 사이에 {@code BoardingCommandService} 의 대기 시간 조회도 매번 기본값 대체 경로를 탄다 —
     * 동작은 하지만 "등록 시점에 값이 있다" 는 엔티티의 전제와 어긋난다.
     */
    public AcademyRegisterResponse register(AcademyRegisterRequest request) {
        List<AcademyWarning> warnings = new ArrayList<>();
        if (academyRepository.existsByNameAndRegion(request.name(), request.region())) {
            warnings.add(AcademyWarning.DUPLICATE_NAME_REGION);
        }
        Academy academy = Academy.register(academyCodeGenerator.generate(),
                request.name(), request.region(), request.address(), request.contact());
        // memo 는 정적 팩토리가 받지 않는다 — 등록 시점의 식별 정보가 아니라 운영 중 붙이고 지우는
        // 내부 메모라, 생성 인자를 6개로 늘리는 대신 수정과 같은 자리를 쓴다.
        academy.update(new AcademyProfile(null, null, null, null, request.memo()));
        Academy saved = academyRepository.save(academy);
        academySettingRepository.save(AcademySetting.forAcademy(saved.getId()));
        return AcademyRegisterResponse.from(saved, warnings);
    }

    /**
     * 학원 정보를 고치거나 비활성화한다(API_SPEC §6.3 PATCH).
     *
     * <p>비활성화는 신규 가입만 막고 <b>기존 사용자의 로그인은 유지</b>한다(ACAD-04) — 이 메서드가 계정
     * 상태를 함께 건드리지 않는 것이 그 요건을 지키는 방식이다. 운행 중 기사가 로그아웃되는 것을 막는다.
     *
     * @return 수정된 학원의 식별자 — 응답 조립은 조회 쪽이 맡는다(§1.9 "변경 후 자원 상태를 반환")
     */
    public Long update(Long academyId, AcademyUpdateRequest request) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        academy.update(new AcademyProfile(requirePresent(request.name()), requirePresent(request.region()),
                request.address(), request.contact(), request.memo()));
        if (request.status() != null) {
            academy.changeStatus(parseStatus(request.status()));
        }
        return academy.getId();
    }

    /**
     * 필수 항목은 <b>보내지 않는 것</b>만 허용하고 빈 문자열은 거부한다 — 공백만 남기면 목록에서 이름
     * 없는 학원이 되어 가입 화면에서 고를 수 없게 되는데, 저장은 조용히 성공한다.
     */
    private String requirePresent(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private AcademyStatus parseStatus(String status) {
        try {
            return AcademyStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
