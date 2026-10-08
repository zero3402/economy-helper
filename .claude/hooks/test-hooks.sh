#!/usr/bin/env bash
# 훅 스모크 — 입력 JSON 하나에 기대 exit code 하나. 훅을 고친 뒤 `bash .claude/hooks/test-hooks.sh`.
# CI에 넣지 않는다(.claude/는 에이전트 설정이라 빌드와 무관하다).
cd "$(dirname "$0")" || exit 1
fail=0

expect() { # 훅 기대값 명령
  local hook=$1 want=$2 command=$3
  local got
  printf '{"tool_input":{"command":%s}}' "$(printf '%s' "$command" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')" \
    | bash "$hook" >/dev/null 2>&1
  got=$?
  if [ "$got" != "$want" ]; then
    echo "FAIL $hook ($want 기대, $got): $command"
    fail=1
  fi
}

# block-env.sh — 2면 막는다
expect block-env.sh 2 'cat backend/.env'
expect block-env.sh 2 '. ./.env'
expect block-env.sh 2 'cp .env.production /tmp/x'
expect block-env.sh 2 'docker compose -f docker-compose.prod.yml config'
expect block-env.sh 0 'cat backend/.env.example'
expect block-env.sh 0 'node -e "console.log(process.env.PORT)"'
expect block-env.sh 0 'git status'

# golden-reminder.sh — 늘 0이다(경고만). 경고가 나오는지는 사람이 본다
expect golden-reminder.sh 0 'git commit -m x'
expect golden-reminder.sh 0 'git -C backend commit -am x'

# golden-reminder.sh의 판정 — 커밋 명령을 알아보는가
for command in 'git commit -m x' 'git -C backend commit -m x' 'git -c user.name=a commit -m x'; do
  printf '%s' "$command" | grep -Eq 'git( +-[Cc] +[^ "]+)* +commit([ "]|$)' \
    || { echo "FAIL 커밋으로 못 알아봄: $command"; fail=1; }
done
for command in 'git commit-tree abc' 'git log --grep commit'; do
  printf '%s' "$command" | grep -Eq 'git( +-[Cc] +[^ "]+)* +commit([ "]|$)' \
    && { echo "FAIL 커밋이 아닌데 알아봄: $command"; fail=1; }
done

[ "$fail" = 0 ] && echo "hooks ok"
exit "$fail"
