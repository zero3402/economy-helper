#!/usr/bin/env bash
# Bash 명령이 .env를 건드리면 막는다(exit 2 → Claude Code가 명령을 실행하지 않고 이유를 모델에 돌려준다).
#
# 권한 거부(settings.json의 Read/Edit deny)는 Read·Edit 도구만 막는다 — `cat .env`, `. ./.env`,
# `grep KEY .env`처럼 셸로 읽는 길은 이 훅이 막는다. .env.example은 키 이름만 있어 허용한다.
#
# jq 없이 돈다(Git Bash·macOS 기본 도구만). 입력 JSON 전체(설명문 포함)를 본다 — 오탐보다 누락이
# 더 비싸다. 알려진 한계: 글자에 .env가 안 나오는 우회(`.en?` 글롭, 변수 조립, `grep -r KEY backend/`)는
# 못 막는다 — 그래서 셸 검색 명령을 허용목록에 넣지 않는다(CLAUDE.md).
input=$(cat)
# 이름이 닮았을 뿐 파일이 아닌 것은 지운 뒤에 본다 — .env.example(키 이름뿐)과 node의 process.env.X
stripped=${input//.env.example/}
stripped=${stripped//process.env/}
# docker compose … config는 env_file을 풀어 비밀값을 그대로 찍는다 — 글자에 .env가 없어도 막는다
if printf '%s' "$stripped" | grep -Eq '\.env([^A-Za-z0-9_]|$)|\.env\.|compose[^|;&]* config([^A-Za-z0-9_-]|$)'; then
  echo "차단: .env에는 API 비밀키가 있어 에이전트가 읽거나 셸에 풀 수 없다. 키 이름은 backend/.env.example을 보고, 실제 키가 필요한 실행(bootRun·실물 감사)은 사람이 자기 터미널에서 돌린다." >&2
  exit 2
fi
exit 0
