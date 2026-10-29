package src.backend.account.query;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.dto.SignupRequestAcademyResponse;
import src.backend.account.dto.SignupRequestSummaryResponse;
import src.backend.account.dto.StaffSignupRequestSummaryResponse;
import src.backend.account.entity.Account;
import src.backend.account.entity.SignupRequest;
import src.backend.account.repository.AccountRepository;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.response.PageResponse;

/** 가입 요청 한 페이지에 신청자·학원·재직 관계자 정보를 채워 응답 행으로 옮기는 조립기 — 조회는 페이지 단위로 한 번에 한다. */
@Component
@RequiredArgsConstructor
class SignupRequestRowAssembler {

    private final AccountRepository accountRepository;

    private final AcademyRepository academyRepository;

    private final AcademyStaffRepository academyStaffRepository;

    /** 관계자 목록 한 페이지를 응답 행으로 옮긴다 — 같은 학원 소속 계정만 이름·연락처를 채운다. */
    PageResponse<SignupRequestSummaryResponse> staffItems(Page<SignupRequest> page, Long academyId) {
        Map<Long, Account> accounts = accountsOf(page.getContent(), academyId);
        return PageResponse.of(page, page.getContent().stream()
                .filter(signupRequest -> accounts.containsKey(signupRequest.getAccountId()))
                .map(signupRequest -> {
                    Account account = accounts.get(signupRequest.getAccountId());
                    return SignupRequestSummaryResponse.of(signupRequest, account.getName(), account.getPhone());
                })
                .toList());
    }

    /**
     * 메인 관리자 목록 한 페이지를 응답 행으로 옮긴다 — 학원과 재직 관계자 수를 <b>한 페이지분 한 번에</b> 모은다.
     * 요청마다 조회하면 한 페이지(최대 100건)가 질의 200건이 된다.
     */
    PageResponse<StaffSignupRequestSummaryResponse> adminItems(Page<SignupRequest> page) {
        if (page.getContent().isEmpty()) {
            return PageResponse.of(page, List.of());
        }
        List<Long> academyIds = page.getContent().stream().map(SignupRequest::getAcademyId).distinct().toList();
        Map<Long, Long> currentStaffAccountIds = currentStaffAccountIds(academyIds);
        Map<Long, Account> accounts = accountsOf(page.getContent(), currentStaffAccountIds);
        Map<Long, Academy> academies = academiesById(academyIds);
        Map<Long, Long> staffCounts = staffCountsOf(academyIds);

        return PageResponse.of(page, page.getContent().stream()
                .filter(signupRequest -> accounts.containsKey(signupRequest.getAccountId())
                        && academies.containsKey(signupRequest.getAcademyId()))
                .map(signupRequest -> adminRow(signupRequest,
                        accounts.get(signupRequest.getAccountId()),
                        academies.get(signupRequest.getAcademyId()),
                        staffCounts.getOrDefault(signupRequest.getAcademyId(), 0L),
                        currentStaffOf(currentStaffAccountIds.get(signupRequest.getAcademyId()), accounts)))
                .toList());
    }

    private StaffSignupRequestSummaryResponse adminRow(SignupRequest signupRequest, Account account, Academy academy,
            long staffCount, StaffSignupRequestSummaryResponse.CurrentStaff currentStaff) {
        return StaffSignupRequestSummaryResponse.of(signupRequest, account.getName(), account.getPhone(),
                SignupRequestAcademyResponse.from(academy), staffCount, currentStaff);
    }

    private Map<Long, Academy> academiesById(List<Long> academyIds) {
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Function.identity()));
    }

    /** 학원별 재직 관계자 수. */
    private Map<Long, Long> staffCountsOf(List<Long> academyIds) {
        return academyStaffRepository.countByAcademyIdInGroupedByAcademyId(academyIds, StaffStatus.ACTIVE).stream()
                .collect(Collectors.toMap(AcademyCount::getAcademyId, AcademyCount::getTotal));
    }

    /** 학원별 재직 관계자 계정 id — 재직 관계자는 학원당 최대 1명이다(정원). */
    private Map<Long, Long> currentStaffAccountIds(List<Long> academyIds) {
        return academyStaffRepository.findAllByAcademyIdInAndStatus(academyIds, StaffStatus.ACTIVE).stream()
                .collect(Collectors.toMap(AcademyStaff::getAcademyId, AcademyStaff::getAccountId));
    }

    /** 신청자 계정과 재직 관계자 계정을 한 번에 읽는다. */
    private Map<Long, Account> accountsOf(List<SignupRequest> requests, Map<Long, Long> currentStaffAccountIds) {
        List<Long> accountIds = Stream.concat(requests.stream().map(SignupRequest::getAccountId),
                currentStaffAccountIds.values().stream()).distinct().toList();
        return accountRepository.findAllByIdIn(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
    }

    /** 재직 관계자 계정을 표시값으로 옮긴다 — 재직자가 없으면(계정 id 가 없으면) {@code null} 이다. */
    private StaffSignupRequestSummaryResponse.CurrentStaff currentStaffOf(Long accountId,
            Map<Long, Account> accounts) {
        Account account = accountId == null ? null : accounts.get(accountId);
        return account == null ? null : StaffSignupRequestSummaryResponse.CurrentStaff.from(account);
    }

    /**
     * 요청 행에 이름·연락처를 채운다 — {@code signup_request} 에 복제하지 않고 {@code account} 에서
     * 가져오므로(ERD §3.1) 신청자가 연락처를 바꾸면 큐도 함께 따라간다.
     *
     * <p>계정을 못 찾은 행은 응답에서 빠진다 — {@code account_id} 가 NOT NULL FK 라 정상 상태에서는
     * 생길 수 없고, 그 한 행 때문에 승인 큐 전체가 실패하는 것보다 낫다.
     */
    private Map<Long, Account> accountsOf(List<SignupRequest> requests, Long academyId) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        return accountRepository
                .findAllByAcademyIdAndIdIn(academyId, requests.stream().map(SignupRequest::getAccountId).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
    }
}
