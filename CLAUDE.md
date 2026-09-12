# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. These mirror
the homeostat core repo's, so the two sessions work the same way.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial
tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If
yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make
it work") require constant clarification.

## 5. This repo specifically

**The protocol is not ours to change.** Every topic, payload, QoS and
retain flag this app uses is specified in
`docs/companion-protocol.md` in the
[homeostat](https://github.com/freol35241/homeostat) repo. Read it before
touching anything that talks to the broker. Never invent a topic, rename
a field, or add one "while we're here": the house side is an adapter that
will not understand it, and the contract is normative. If the app needs a
change to the protocol, open an issue on homeostat and say what the app
needs and why — that is the change process, and it is deliberate.

**What this app is not** is as load-bearing as what it is: not a
dashboard, not an owner surface, not a WireGuard client, never a bus
client. README.md has the list, homeostat's design record has the
reasoning. A feature request that crosses one of those lines is a
conversation, not a commit.

**No device in CI.** `./gradlew test lint assembleDebug` is everything
that can be checked automatically. Reconnect under Doze, real geofence
transitions and DND bypass are verified by hand on a phone — so when you
change any of them, say plainly that it is unverified rather than
implying a green build covered it.
