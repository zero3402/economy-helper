@AGENTS.md

## Claude Code

- 도메인 패키지의 `CLAUDE.md`(`@AGENTS.md` 한 줄)가 그 폴더 파일을 열 때 그 `AGENTS.md`를 자동으로 붙인다. 그래도 「시작」 절차는 그대로 따른다.
- `.env`는 권한 거부와 훅(`.claude/hooks/block-env.sh`)으로 막혀 있다.
- 스킬: `/real-audit`(실물 감사) · `add-source`(새 외부 출처 점검). 에이전트: `domain-reviewer`(도메인 원칙으로 diff 검토).
