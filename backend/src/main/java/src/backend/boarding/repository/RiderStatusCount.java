package src.backend.boarding.repository;

import src.backend.boarding.entity.RiderStatus;

/** 한 회차의 라이더 수를 상태별로 묶은 한 줄({@link RunRiderRepository#countGroupedByStatus}). */
public interface RiderStatusCount {

    RiderStatus getStatus();

    long getTotal();
}
