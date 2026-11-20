---
name: open-pr
description: Commit, push and open a GitHub pull request for DanceSonic work, safely. Use when the user asks to commit, push, or open a PR. Covers splitting unrelated changes onto their own branch, the PR body file, and avoiding a hung gh call.
---

# Open a pull request

Only commit or push when the user asks. "Commit, push and open PR" is that request; "update X" alone is not.

## Before committing

1. Read `git status` and `git branch --show-current`. The base is `main` unless the user says otherwise.
2. Do not stage `.claude/` or `.github/` unless the user names them. They are often untracked local files.
3. Stage by path, never `git add -A`. Check that nothing from `local.properties`, keystores or generated files is in the list.
4. Run the checks that AGENTS.md requires for code changes (see `android-build-test`). Docs-only changes need no build.
5. If the change does not belong to the current branch's issue, move it to its own branch first:
   `git stash push -- <paths>`, `git checkout -b <topic> main`, `git stash pop`.
   Do not add an unrelated change to an open PR.

## Commit

- Message: a short subject, then a body saying what changed and why. Use `Refs #N` when the commit is part of an issue. Use `Closes #N` when the change completes it. GitHub closes an issue only when a merged PR into the default branch contains the keyword, so a commit alone is not enough.
- Choose the keyword before opening the PR. The PR body is what GitHub reads at merge time. If the PR completes an issue, its body must say `Closes #N`, not `Refs #N`. Otherwise the issue stays open after the merge, which happened with #89 and had to be closed by hand.
- End with the attribution line from the session's system reminder, for example `Co-Authored-By: Claude Haiku 5.5 <noreply@anthropic.com>`.
- Use `git commit -F -` with a heredoc, or `-F file`.

## Push

`git push -u origin <branch>`. Creating a branch on the remote is normal; pushing to `main` is not.

## Open the PR

Write the body to a file in the scratchpad, then pass `--body-file`. Give the title and base explicitly, and end the command with `< /dev/null` and a timeout:

```bash
cat > "$SCRATCHPAD/pr_body.md" <<'EOF2'
## What changed
...
Closes #N

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF2
timeout 110 gh pr create --base main --head <branch> --title "<title>" \
  --body-file "$SCRATCHPAD/pr_body.md" < /dev/null
```

Do not pipe `gh` into `tail` or `head` in a long-running shell, and do not put a stray `cat > file` before it. Both have hung the shell: an open stdin or a buffered pipe keeps the command from finishing. If a command still exceeds its timeout, check with `gh pr list --head <branch>` before running it again, so a second PR is not created.

The PR body should state what changed, why, how it was tested (with counts), and any security or migration impact. Mention known flakes by name and say whether they passed on re-run.

## After

Run `gh pr view <branch> --json number,url,state` and give the user the URL. Say whether the PR refers to an issue or closes it. After a merge, check that the issue closed with `gh issue view N --json state`. If it did not, the PR used `Refs`: close the issue with a comment naming the merged PR.
