---
name: grill-with-docs
description: "A relentless interview to sharpen a plan or design, which also creates docs (ADR's and glossary) as we go. Invoke when the user wants to stress-test a plan while capturing domain terminology and architectural decisions in CONTEXT.md and ADRs."
disable-model-invocation: true
---

Run a `/grilling` session, using the `/domain-modeling` skill.

This means you should:

1. **Interview relentlessly** — Apply the `grilling` discipline: walk down each branch of the decision tree, resolving dependencies between decisions one-by-one. Ask questions **one at a time**, waiting for feedback before continuing. For each question, provide your recommended answer.

2. **Build the domain model as you go** — Apply the `domain-modeling` discipline: when a term is resolved, write it to `CONTEXT.md` inline (not batched at the end). Offer ADRs sparingly, only when a decision is hard to reverse, surprising without context, and the result of a real trade-off.

3. **Facts vs. decisions** — If a *fact* can be found by exploring the codebase (filesystem, tools), look it up rather than asking. The *decisions*, though, are the user's — put each one to them and wait for their answer.

4. **Do not act** until the user confirms you have reached a shared understanding.

Refer to the `domain-modeling` skill's `CONTEXT-FORMAT.md` and `ADR-FORMAT.md` for the exact file formats.
