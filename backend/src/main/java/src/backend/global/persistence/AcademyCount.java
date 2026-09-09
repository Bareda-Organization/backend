package src.backend.global.persistence;

/**
 * 학원별 집계 1행 — {@code GROUP BY academy_id} 결과를 담는 조회 전용 투영이다.
 *
 * <p>목록 응답이 학원마다 부가 집계(관계자 수·소속 사용자 수)를 실어야 하는데, 학원 하나에 질의를
 * 하나씩 붙이면 한 페이지(최대 100건)가 질의 200건이 된다. 한 번에 모아 오려면 결과가 두 컬럼이라
 * 담을 타입이 필요하다.
 */
public interface AcademyCount {

    Long getAcademyId();

    long getTotal();
}
