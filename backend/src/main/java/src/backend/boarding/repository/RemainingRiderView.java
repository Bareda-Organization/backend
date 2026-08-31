package src.backend.boarding.repository;

/** {@link RunRiderRepository#findRemainingByRunIdAndStatus} 의 투영 — 이름·정차지명까지 한 번에 담는다. */
public interface RemainingRiderView {

    Long getRiderId();

    String getName();

    String getStopName();
}
