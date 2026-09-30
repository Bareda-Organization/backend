package src.backend.academy.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
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
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 학원 저장 담당 — 트랜잭션 경계가 여기다. 지오코딩 포트({@code GeocodingClient})를 들지 않는다.
 *
 * <p>검증({@link AcademyCommandService})과 저장을 다른 빈으로 갈라 둔 것이 §7 규칙 16 을 지키는 방식이다
 * ({@code WeeklyAddressStore} 와 같은 구조) — 좌표는 이미 구해진 값으로 넘어온다.
 */
@Component
@RequiredArgsConstructor
@Transactional
public class AcademyStore {

    private final AcademyRepository academyRepository;

    private final AcademySettingRepository academySettingRepository;

    private final AcademyCodeGenerator academyCodeGenerator;

    /**
     * 학원과 기본 설정을 한 트랜잭션에 저장한다 — {@code point} 가 {@code null} 이면 좌표 없이 저장한다.
     *
     * <p>설정({@link AcademySetting})을 같은 트랜잭션에서 만드는 이유는 그 팩토리 javadoc 이 "학원 등록과 같은
     * 트랜잭션" 을 전제로 두기 때문이다 — 안 만들면 첫 조회가 자가 치유 경로로 즉석 생성해야 한다.
     */
    public AcademyRegisterResponse register(AcademyRegisterRequest request, GeocodedPoint point) {
        List<AcademyWarning> warnings = new ArrayList<>();
        if (academyRepository.existsByNameAndRegion(request.name(), request.region())) {
            warnings.add(AcademyWarning.DUPLICATE_NAME_REGION);
        }
        Academy academy = Academy.register(academyCodeGenerator.generate(),
                request.name(), request.region(), request.address(), request.contact());
        // memo 는 정적 팩토리가 받지 않는다 — 등록 시점의 식별 정보가 아니라 운영 중 붙이고 지우는
        // 내부 메모라, 생성 인자를 6개로 늘리는 대신 수정과 같은 자리를 쓴다.
        academy.update(new AcademyProfile(null, null, null, null, request.memo()));
        if (point != null) {
            academy.assignCoordinates(point.lat(), point.lng());
        }
        Academy saved = academyRepository.save(academy);
        academySettingRepository.save(AcademySetting.forAcademy(saved.getId()));
        return AcademyRegisterResponse.from(saved, warnings);
    }

    /**
     * 학원 정보를 고치고 좌표를 맞춘다 — {@code address} 를 보낸 수정이면 좌표를 {@code point} 로 바꾸고
     * ({@code null} 이면 비운다), 보내지 않은 수정이면 좌표를 건드리지 않는다.
     *
     * @return 수정된 학원의 식별자 — 응답 조립은 조회 쪽이 맡는다(§1.9)
     */
    public Long update(Long academyId, AcademyUpdateRequest request, GeocodedPoint point) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        academy.update(new AcademyProfile(requirePresent(request.name()), requirePresent(request.region()),
                request.address(), request.contact(), request.memo()));
        if (request.address() != null) {
            applyCoordinates(academy, point);
        }
        if (request.status() != null) {
            academy.changeStatus(parseStatus(request.status()));
        }
        return academy.getId();
    }

    private void applyCoordinates(Academy academy, GeocodedPoint point) {
        if (point == null) {
            academy.clearCoordinates();
            return;
        }
        academy.assignCoordinates(point.lat(), point.lng());
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
