package src.backend.location.repository.spec;

import java.util.Optional;

import src.backend.location.dto.BusLocationPing;

/**
 * 버스 실시간 위치 저장소의 계약 — "버스별 최신 좌표 1건"을 보관한다.
 * 학생 단위 {@link LocationRepository}와 동일한 설계(휘발성, in-memory 우선)를 버스 단위로 미러링한다(F1).
 */
public interface BusLocationRepository {

    /** 버스의 최신 좌표를 저장(있으면 덮어쓴다). */
    void save(BusLocationPing ping);

    /** 버스의 최신 좌표. 아직 보고가 없으면 empty. */
    Optional<BusLocationPing> findLatest(Long busId);
}
