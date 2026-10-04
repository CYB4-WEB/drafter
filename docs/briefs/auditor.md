# Brief: auditor — independent code audit of Daftar

You did not write this code. Your job is to find what is broken, missing or misleading — not to praise it.

## Inputs
- Requirements: `docs/SPEC.md` (user's original list + upgrades), `docs/DESIGN.md`, all briefs in `docs/briefs/`, `docs/AGENT_RULES.md`.
- Agent logs: `docs/logs/*.md` (their self-checks — verify, don't trust).
- Code: `app/src/main/**`.

## What to check
1. **Requirement coverage**: for EVERY numbered requirement in SPEC.md (1–15 + upgrades) find the code that implements it. Mark IMPLEMENTED / PARTIAL / MISSING with file:line evidence.
2. **Brief acceptance criteria**: for each agent brief, re-verify every criterion by reading the code. Flag any self-check claim that the code does not support.
3. **Correctness bugs** (highest value): crashes (null `!!`, index out of range, main-thread IO on big files, unclosed resources, PdfRenderer page not closed, bitmap OOM), wrong coordinate math, lost data (save paths, sidecars on rename/move/delete, ink saved to wrong file), race conditions between UI and background threads, state not surviving rotation/split-screen, broken deep links / PendingIntents, alarms not rescheduled.
4. **Integration**: shared contracts used correctly (InkEditorScaffold, PageSource, Storage sidecars, Nav, strings existing in BOTH values and values-ar). Every `R.string.x` used must exist in both locales — script it (grep).
5. **i18n/RTL**: hard-coded user-visible English strings in Kotlin; left/right instead of start/end; non-mirrored directional icons.
6. **Design compliance**: gradients, shadows/elevation, colours not from tokens, inconsistent radii.
7. **Security/privacy**: exported components without need, FileProvider paths too broad, intents with implicit targets carrying URIs without grant flags.

## Rules
- Read-only: do NOT edit any source file. Only write `docs/AUDIT.md`.
- You may run the compile command from AGENT_RULES.md and grep/scripts.
- Each finding: ID, severity (BLOCKER / MAJOR / MINOR), file:line, what's wrong, concrete failure scenario, suggested fix (short code if useful).
- Order findings by severity. Include the coverage table (requirement → status → evidence). Be concrete; no generic advice.
- Final message: counts per severity + top 10 findings in one line each.
