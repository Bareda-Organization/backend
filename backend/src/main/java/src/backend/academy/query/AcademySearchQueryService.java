package src.backend.academy.query;

import java.util.List;

import org.springframework.stereotype.Service;

import src.backend.academy.dto.AcademySearchResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.repository.AcademyRepository;

/** 가입용 학원 검색 유스케이스(AUTH-02, API_SPEC §2.1). */
@Service
public class AcademySearchQueryService {

    private final AcademyRepository academyRepository;

    public AcademySearchQueryService(AcademyRepository academyRepository) {
        this.academyRepository = academyRepository;
    }

    /** 비활성 학원은 제외한다 — 신규 가입 대상에서 빼는 O-01 규칙. */
    public AcademySearchResponse search(String q) {
        List<Academy> academies = academyRepository.searchByStatus(AcademyStatus.ACTIVE, q);
        return AcademySearchResponse.from(academies);
    }
}
