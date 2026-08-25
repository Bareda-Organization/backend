package src.backend.academy.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import src.backend.academy.dto.AcademySearchResponse;
import src.backend.academy.query.AcademySearchQueryService;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.PublicEndpoint;

/** 가입용 학원 검색 API(API_SPEC §2.1). */
@RestController
public class AcademySearchController {

    private final AcademySearchQueryService academySearchQueryService;

    public AcademySearchController(AcademySearchQueryService academySearchQueryService) {
        this.academySearchQueryService = academySearchQueryService;
    }

    @PublicEndpoint
    @GetMapping("/academies/search")
    public ApiResponse<AcademySearchResponse> search(@RequestParam String q) {
        return ApiResponse.ok(academySearchQueryService.search(q));
    }
}
