#!/usr/bin/env bash
# Bash 명령이 .env를 건드리면 막는다(exit 2 → Claude Code가 명령을 실행하지 않고 이유를 모델에 돌려준다).
#
# 권한 거부(settings.json의 Read/Edit deny)는 Read·Edit 도구만 막는다 — `cat .env`, `. ./.env`,
# `grep KEY .env`처럼 셸로 읽는 길은 이 훅이 막는다. .env.example은 키 이름만 있어 허용한다.
#
# jq 없이 돈다(Git Bash·macOS 기본 도구만). 입력 JSON 전체를 보지만 cwd·세션 경로에 `.env`가
# 들어갈 일은 없어 오탐보다 누락이 더 비싸다는 쪽을 택했다.
input=$(cat)
stripped=${input//.env.example/}
if printf '%s' "$stripped" | grep -Eq '\.env([^A-Za-z0-9_]|$)|\.env\.'; then
  echo "차단: .env에는 API 비밀키가 있어 에이전트가 읽거나 셸에 풀 수 없다. 키 이름은 backend/.env.example을 보고, 실제 키가 필요한 실행(bootRun·실물 감사)은 사람이 자기 터미널에서 돌린다." >&2
  exit 2
fi
exit 0
