package src.backend.academy.query;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademySearchResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.persistence.LikeEscape;

/** 가입용 학원 검색 유스케이스(AUTH-02, API_SPEC §2.1). */
@Service
@RequiredArgsConstructor
public class AcademySearchQueryService {

    /**
     * 비인증 공개 엔드포인트라 {@code q="%"} 같은 값이 전체 활성 학원을 반환하지 않도록 거는 상한.
     * API_SPEC §2.1 은 구체적인 상한값을 못박지 않아, 가입 화면에서 학원을 고르는 UX 상 한 화면에
     * 다 보여줄 만한 크기로 자체 결정한다.
     */
    private static final int MAX_RESULTS = 20;

    private final AcademyRepository academyRepository;

    /** 비활성 학원은 제외한다 — 신규 가입 대상에서 빼는 O-01 규칙. */
    public AcademySearchResponse search(String q) {
        Pageable pageable = PageRequest.of(0, MAX_RESULTS);
        List<Academy> academies = academyRepository.searchByStatus(AcademyStatus.ACTIVE, LikeEscape.escape(q),
                pageable);
        return AcademySearchResponse.from(academies);
    }
}
