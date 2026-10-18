#!/usr/bin/env python3
"""R46-LOAD — 폴링·시청 세션 시나리오(scenario5)가 쓸 접근 토큰을 회차 직전에 미리 발급한다.

왜 k6 안에서 로그인하지 않는가 — 학부모 수백 명이 같은 순간 BCrypt 로그인을 하면 그 CPU 가 측정 구간(누적 CPU 차)에
섞인다. 실제 앱은 이미 로그인된 채 폴링하므로 로그인은 측정 밖에 둔다. 접근 토큰 유효시간은 15분이라 회차 직전에
발급해야 한다(`app.jwt.access-token-validity-seconds: 900`).

실행: ./r46_mint_tokens.py <출력.json> [학부모 수=600]
환경변수 PARENT_NAME_LIKES — 학부모를 뽑을 학생 이름 패턴(쉼표로 여럿 · 기본 'LOADCAP-%'). 학생 목적지 시험은 'LOADCAP-%,L3DEST-%'.
학부모 항목에는 접근 토큰(token) 말고 재발급 토큰(refresh)도 담긴다 — 토큰 재발급을 걸어 보는 시험(scenario8)이 쓴다.
환경변수 STAFF_SESSIONS · ADMIN_SESSIONS — 세션마다 따로 로그인한 관계자(학원 순환)·메인 관리자 항목을 staff_sessions · admin_sessions 로 더 담는다(기본 0).
재발급 토큰은 쓸 때마다 바뀌어 한 로그인을 여러 세션이 나눠 쓸 수 없다 — 실제 탭도 로그인마다 재발급 토큰이 따로다.
"""
import json
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get('BASE_URL', 'http://localhost:18080') + '/api/v1'
PG = ['docker', 'exec', os.environ.get('PG_CONTAINER', 'school-bus-postgres-1'),
      'psql', '-U', 'schoolbus', '-d', os.environ.get('DB_NAME', 'schoolbus_load'), '-t', '-A', '-F,', '-c']
PASSWORD = 'password'


def query(sql):
    out = subprocess.run(PG + [sql], check=True, capture_output=True, text=True).stdout
    return [line.split(',') for line in out.splitlines() if line.strip()]


def login_data(login_id):
    req = urllib.request.Request(
        f'{BASE}/auth/login',
        data=json.dumps({'login_id': login_id, 'password': PASSWORD}).encode(),
        headers={'Content-Type': 'application/json', 'X-Client-Type': 'app'})
    with urllib.request.urlopen(req, timeout=60) as res:
        return json.load(res)['data']


def login(login_id):
    return login_data(login_id)['access_token']


def main(out_path, parent_count):
    # md5 순서 — 첫 학원·첫 버스 쪽으로 쏠리지 않게 2,000명 중 고르게 뽑는다.
    likes = ' OR '.join(f"s.name LIKE '{p}'" for p in os.environ.get('PARENT_NAME_LIKES', 'LOADCAP-%').split(','))
    parents = query(f"SELECT s.id, lower(s.name) || '-par' FROM student s WHERE {likes} "
                    f"ORDER BY md5(s.id::text) LIMIT {parent_count}")
    staff = query("SELECT a.id, lower(a.code) || '-staff', "
                  "(SELECT min(r.id) FROM run r JOIN bus b ON b.id = r.bus_id "
                  " WHERE b.academy_id = a.id AND b.bus_no LIKE 'LOADCAP-%' AND r.direction = 'to_academy') "
                  "FROM academy a WHERE a.code LIKE 'LOADCAP-%' ORDER BY a.id")
    # staffA(학원 1) — 위치 시험용 회차 100개가 몰린 학원이라 이 계정의 /staff/runs/live 만 움직이는 회차 100개를 읽는다.
    # 학원 10곳이 각 10대를 읽는 총량과 같아지게 폴링 탭 한 개로 넣는다. 명단 조회용 회차는 없다(runId 빈칸).
    staff += [[row[0], row[1], ''] for row in query("SELECT academy_id, login_id FROM account WHERE login_id = 'staffA'")]
    with ThreadPoolExecutor(8) as pool:
        parent_logins = list(pool.map(login_data, [row[1] for row in parents]))
        staff_tokens = list(pool.map(login, [row[1] for row in staff]))
    admin_token = login('sysadmin')
    n_staff, n_admin = int(os.environ.get('STAFF_SESSIONS', 0)), int(os.environ.get('ADMIN_SESSIONS', 0))
    loadcap = [s for s in staff if s[2]]  # runId 가 있는 LOADCAP 학원 계정만 — 학원마다 돌아가며 쓴다
    with ThreadPoolExecutor(8) as pool:
        staff_sessions = list(pool.map(login_data, [loadcap[i % len(loadcap)][1] for i in range(n_staff)]))
        admin_sessions = list(pool.map(login_data, ['sysadmin'] * n_admin))
    doc = {
        'staff_sessions': [{'academyId': int(loadcap[i % len(loadcap)][0]), 'token': d['access_token'], 'refresh': d.get('refresh_token')} for i, d in enumerate(staff_sessions)],
        'admin_sessions': [{'token': d['access_token'], 'refresh': d.get('refresh_token')} for d in admin_sessions],
        'parents': [{'studentId': int(p[0]), 'token': d['access_token'], 'refresh': d.get('refresh_token')} for p, d in zip(parents, parent_logins)],
        'staff': [{'academyId': int(s[0]), 'runId': int(s[2]) if s[2] else None, 'token': t} for s, t in zip(staff, staff_tokens)],
        'admin': admin_token,
    }
    with open(out_path, 'w') as f:
        json.dump(doc, f)
    print(f'토큰 발급: 학부모 {len(parents)} · 관계자 {len(staff)} · 메인 관리자 1 → {out_path}')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 600)
