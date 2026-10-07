@AGENTS.md

## Claude Code

- 도메인 패키지의 `CLAUDE.md`(`@AGENTS.md` 한 줄)가 그 폴더 파일을 열 때 그 `AGENTS.md`를 자동으로 붙인다. 그래도 「시작」 절차는 그대로 따른다.
- `.env`는 권한 거부와 훅(`.claude/hooks/block-env.sh`)으로 막혀 있다.
- ⚠️ **셸 검색 명령(`grep`·`rg`·`find`)을 허용목록에 넣지 않는다.** 훅은 명령 글자에서 `.env`를 찾는데,
  `grep -rn KIS_API backend/`에는 그 글자가 없어 그냥 통과한다 — 허용목록에 있으면 묻지도 않고 비밀키가 찍힌다.
  트리 검색은 `Grep`·`Glob` 도구를 쓴다(권한 거부 규칙이 그쪽에는 걸린다).
- 골든 파일이 바뀐 채 커밋하려 하면 `.claude/hooks/golden-reminder.sh`가 **경고한다**(막지는 않는다 — 읽었는지는 사람만 안다).
- 스킬: `/real-audit`(실물 감사) · `/add-source`(새 외부 출처 점검). 에이전트: `domain-reviewer`(도메인 원칙으로 diff 검토).
