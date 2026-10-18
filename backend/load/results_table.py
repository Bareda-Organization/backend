#!/usr/bin/env python3
"""회차 라벨별 환경 값(<라벨>_env.json)을 마크다운 표로 모은다 — 측정 보고서의 메모리 · CPU 표용.

실행: ./results_table.py <라벨> [라벨 …]
열: 컨테이너 메모리 최고(cgroup) · 힙 사용 최대 · 힙 확정 최대 · 비힙 · 직접 버퍼 · 스레드 · GC 정지 최대 · 앱 / Postgres / Redis CPU 초 · 앱 평균 코어 · 합 평균 코어 · CPU 제한 대기 초
"""
import json
import sys
from pathlib import Path

RES = Path(__file__).resolve().parent / 'results'
print('| 회차 | 컨테이너 메모리 최고 MB | 힙 사용 최대 MB | 힙 확정 최대 MB | 비힙 MB | 직접 버퍼 MB | 스레드 | GC 정지 최대 s | 앱 CPU 초 | Postgres CPU 초 | Redis CPU 초 | 앱 평균 코어 | 앱+PG+Redis 평균 코어 | CPU 제한 대기 초 | OOMKilled |')
print('|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|:-:|')
for label in sys.argv[1:]:
    d = json.loads((RES / f'{label}_env.json').read_text())
    j, c = d['jvm_max'], d['cpu_sec']
    print(f"| {label} | {d['container_mem_mb']['current_max']:.0f} | {j['heap_mb']:.0f} | {j['heap_committed_mb']:.0f} | {j['nonheap_mb']:.0f} | {j['direct_mb']:.0f} | {j['live_threads']:.0f} | {j['gc_pause_max_s']:.2f} | "
          f"{c['app']:.0f} | {c['postgres']:.0f} | {c['redis']:.0f} | {d['cpu_cores_avg']['app']:.2f} | {d['app_pg_redis_cores_avg_sum']:.2f} | {d['cpu_throttled_sec']:.0f} | {d['oom_killed']} |")
