#!/usr/bin/env bash
# 골든 파일이 바뀐 채로 커밋하려 하면 **경고만** 한다(차단하지 않는다).
#
# docs/testing.md: "골든은 생성한 뒤 눈으로 읽고 나서 커밋한다. 안 읽고 굳히면 그때 있던 버그가
# 「정답」이 된다." 규칙은 있는데 강제하는 장치가 없어서, 테스트가 다시 그려 준 파일이 그대로
# 커밋되는 길이 열려 있었다.
#
# 차단하지 않는 이유: 화면을 일부러 바꾼 커밋이 정상이고, 그걸 막으면 훅을 꺼 버리게 된다.
# 막을 수 없는 것(사람이 읽었는지)을 막는 대신, 읽어야 한다는 사실을 그 자리에 띄운다.
#
# jq 없이 돈다(Git Bash·macOS 기본 도구만) — block-env.sh와 같은 이유다.
input=$(cat)

# git commit 이 아니면 아무 말도 하지 않는다
printf '%s' "$input" | grep -q 'git commit' || exit 0

golden="backend/src/test/resources/golden/messages.txt"
root="${CLAUDE_PROJECT_DIR:-.}"
cd "$root" 2>/dev/null || exit 0

# 스테이지에 올랐든 아니든, HEAD와 다르면 이번 커밋에 실릴 수 있다
git diff --quiet HEAD -- "$golden" 2>/dev/null && exit 0

printf '%s' '{"systemMessage":"골든 파일(golden/messages.txt)이 바뀌었다. 커밋 전에 diff를 눈으로 읽고 그 차이가 의도한 것인지 확인할 것 — 안 읽고 굳히면 그때 있던 버그가 정답이 된다(docs/testing.md).","hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"golden/messages.txt changed. Read `git diff HEAD -- backend/src/test/resources/golden/messages.txt` and confirm every changed line is intended before committing."}}'
exit 0
