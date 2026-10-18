#!/usr/bin/env python3
"""표본 CSV 에서 최대·중간값을 낸다 — 포화 판정에 쓰는 것은 평균이 아니라 최대다."""
import csv, sys, statistics

def col(rows, name):
    out = []
    for r in rows:
        v = r.get(name, '')
        if v not in ('', None):
            try:
                out.append(float(v))
            except ValueError:
                pass
    return out

def main(path):
    rows = list(csv.DictReader(open(path)))
    parts = []
    for name, label, fmt in [
        ('cpu_process', 'cpu', '{:.2f}'), ('heap_mb', 'heap', '{:.0f}MB'), ('rss_mb', 'rss', '{:.0f}MB'),
        ('live_threads', 'thr', '{:.0f}'), ('tomcat_busy', 'tomcatBusy', '{:.0f}'),
        ('hikari_active', 'hikariAct', '{:.0f}'), ('hikari_pending', 'hikariPend', '{:.0f}'),
        ('hikari_acquire_max_s', 'acquireMax', '{:.3f}s'),
        ('out_queued', 'outQ', '{:.0f}'), ('established', 'conns', '{:.0f}'),
        ('heap_committed_mb', 'heapCommitted', '{:.0f}MB'), ('nonheap_mb', 'nonheap', '{:.0f}MB'),
        ('direct_mb', 'direct', '{:.0f}MB'), ('gc_pause_max_s', 'gcPauseMax', '{:.3f}s'),
    ]:
        vals = col(rows, name)
        if not vals:
            continue
        parts.append(f"{label}={fmt.format(max(vals))}(중앙 {fmt.format(statistics.median(vals))})")
    print('  최대 ' + ' · '.join(parts))

if __name__ == '__main__':
    main(sys.argv[1])
