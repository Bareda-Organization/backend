#!/usr/bin/env bash
# 재접속 시험(scenario7 · scenario8) 전 단계 — 세션 2,000개(학생 1,900 · 학원 90 · 관제 10)의 토큰을 회차 시작 전에 발급한다.
# 로그인 BCrypt CPU 가 측정 구간에 섞이지 않게 prod_sized_round.sh 의 PRE_HOOK 으로 r3_mixed.sh 시작 전에 돈다. 세션마다 따로 로그인한다(재발급 토큰이 로그인마다 따로다).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_NAME_LIKES='LOADCAP-%' STAFF_SESSIONS=90 ADMIN_SESSIONS=10 python3 "$DIR/mint_tokens.py" "$DIR/results/${ROUND_LABEL:?}_storm_tokens.json" 1900
