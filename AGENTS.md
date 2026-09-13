# AGENTS.md

## Project Context

This repository is a graduation project: ingredient recognition and intelligent recipe recommendation based on multimodal large models.

Current stack:

- Java 17
- Spring Boot 3
- Maven
- DeepSeek API integration
- Static frontend under `src/main/resources/static`

## Core Working Rules

- Only change files that are directly related to the current task.
- Do not refactor unrelated code.
- Preserve the existing project structure and coding style unless a task requires a focused change.
- Prefer small, reviewable changes over broad edits.
- Read the surrounding code before editing.
- Do not overwrite user changes. If user changes affect the task, work with them.
- All frontend pages must use Chinese as the primary visible language. English copy should only remain for code identifiers, API values, or unavoidable third-party terms.

## Codex Conversation And Workspace Defaults

- When creating a new Codex conversation or task, use `gpt-5.6-luna` by default with speed-priority reasoning (`minimal` when the platform exposes a reasoning-effort setting). Follow an explicitly requested model or reasoning setting instead.
- Do not create a new Git worktree for this project. Work directly in `D:\AI-Search-food`; when code changes are needed, create or switch to a dedicated branch first and never modify `main` directly.

## Technical Discussion And Challenge

- Maintain independent technical judgment. When a user proposal creates a material risk to correctness, usability, feasibility, maintainability, cost, security, or the agreed product goal, raise the concern clearly and discuss it instead of silently following it.
- Explain the concrete reasoning, trade-offs, and a practical alternative. Respect the user's final product decision unless a platform, safety, or technical constraint makes it impossible.
- Keep disagreements factual, respectful, and scoped to the current task. Do not argue without a specific engineering reason or user benefit.

## Required Flow For Code Changes

Before changing code:

- Inspect the relevant files and tests.
- State a short plan when the change is not trivial.
- Keep the scope tied to the requested feature, fix, or documentation update.
- Proceed autonomously with routine engineering work after the task is clear. Do not interrupt the user for step-by-step approval of file inspection, focused backend changes, tests, builds, local service control, or other necessary development operations.
- Before changing frontend layout, visual design, interaction flow, or user-visible frontend functionality, describe the proposed change concisely and wait for the user's explicit approval.
- Platform-required permission prompts may still be necessary and do not replace the frontend approval rule.

After changing code:

- Review the diff before reporting completion.
- Automatically run all relevant verification, including unit tests, integration tests, frontend tests, builds, lint checks, schema checks, and focused browser checks when applicable. Do not ask the user whether these verification steps should be run.
- If verification requires execution outside the sandbox, request the required platform permission directly without first asking in a conversational message. Platform permission dialogs may still appear and cannot be bypassed by repository instructions.
- For this Maven project, prefer:

```powershell
mvn "-Dmaven.repo.local=D:\AI-Search-food\.m2" test
```

- If verification cannot be run, explain the exact reason.
- Ask the user whether to commit the branch and push it to GitHub.
- After each feature is implemented and reviewed, list the remaining unimplemented features for the user to choose from.
- Mark the recommended next feature and wait for the user's selection before starting another feature.

## Automated Test Process Cleanup

- Track every temporary process started by the agent for automated verification, including frontend development servers, backend services, databases, mock services, browser helpers, and their child processes.
- After each Codex verification, keep the Docker Compose test environment and the application services needed for local testing running; do not remove containers, volumes, or the built test environment. Report the retained services and their access information so the user can continue testing locally.
- Stop only one-off helper processes that are not part of the retained local test environment, and verify that their ports have been released.
- Never stop a process started by the user unless the user explicitly requests it.
- If retaining the environment would be unsafe, impossible, or conflict with an explicit user instruction, explain the exact reason before stopping or removing anything.

## Review Requirement

Every completed modification must be reviewed before the final response.

The review should check:

- Whether the change matches the user's request.
- Whether unrelated files or behavior were changed.
- Whether obvious bugs, broken tests, formatting issues, or encoding issues were introduced.
- Whether the change needs additional tests.

## 错误记录要求

- 每次对话中遇到 bug、测试失败、构建错误或运行异常时，在结束前将问题现象、原因、解决方案和验证结果追加记录到 `D:\AI-Search-food\错误报告.txt`。
- 只记录本次对话实际遇到的问题，不记录没有发生的假设性问题；不得在错误报告中写入 API Key、密码、验证码等敏感信息。
- 同一问题经过多轮对话才最终解决时，在该条记录标题开头加 `*`，例如 `* 菜谱详情关闭按钮缺失`。
- 追加记录前先读取现有内容，保留历史记录，不得覆盖已有报告；记录使用中文并注明日期。

## Rollback Requirement

After every modification, treat the current pre-change state as the previous version.

If the user says the Chinese rollback phrase U+56DE U+9000, or says "rollback",
rollback the last modification made by the agent:

- Revert only the agent's most recent changes.
- Do not revert unrelated user changes.
- Check the diff after rollback.
- Report what was reverted.

## Git And GitHub Rules

- Before adding a feature, fixing a bug, adding or changing tests, or making other implementation changes, create and work on a dedicated branch. Do not make these changes directly on `main`.
- Use concise, descriptive branch names that make the purpose clear. Keep a professional type prefix such as `feature/`, `fix/`, `chore/`, `docs/`, or `test/`; write non-technical business and feature terms in Chinese, and keep English only for established technical terms, identifiers, or tool-required names. Examples: `feature/用户密码登录`, `fix/首页响应式布局`, or `docs/更新项目说明`.
- Git worktrees are not used in this project. Keep all work in the current `D:\AI-Search-food` directory and use dedicated branches for code changes; never modify `main` directly.
- Non-code activities, including downloading or installing skills, reading documentation, repository inspection, and other setup work, may use the current worktree without creating a new one.
- Do not commit unless the user explicitly approves.
- Do not push to GitHub unless the user explicitly approves.
- Do not merge branches unless the user explicitly approves.
- After each completed modification, ask whether the user wants the branch committed and pushed to GitHub.
- Keep commit messages concise and task-focused when commits are approved.

## API Key Handling

The user may temporarily provide a DeepSeek API key for local debugging or verification.

Default handling:

- Use temporary environment variables when possible.
- Do not write API keys into source code, README files, committed config files, tests, or generated artifacts by default.
- Do not commit API keys to GitHub.
- If a task truly requires writing an API key to a local file, state the target path and risk first, then wait for explicit confirmation.

## Project-Specific Notes

- Keep DeepSeek configuration compatible with environment variables such as `DEEPSEEK_API_KEY` and `DEEPSEEK_MODEL`.
- Keep backend endpoints and frontend behavior aligned.
- For API behavior changes, update or add focused controller/service tests when practical.
- For UI changes, verify that the static page still works from the Spring Boot app.
- Avoid introducing unnecessary frameworks, build tools, or large dependencies.
