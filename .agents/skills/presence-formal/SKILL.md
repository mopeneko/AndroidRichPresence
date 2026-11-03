---
name: presence-formal
description: Assess formal-verification readiness when AndroidRichPresence gains presence lifecycle, connection, retry, or credential logic. Currently records that no eligible target exists and claims no proof.
---

# Assess presence formal verification

## Current coverage

No property is formally verified. No verifier or model is installed.
At commit `eae67cbd271748ba2631b2a52b28e34fe2ee4505`, production code consists of MainActivity, Greeting, GreetingPreview, and Compose theme code.
`MainActivity.onCreate` renders `Greeting("Android")`.
There is no presence lifecycle, asynchronous connection, retry loop, parser, or credential storage to model.
A model of hypothetical Discord behavior would not verify this application.
This readiness skill is not an executed formal-verification deliverable.

## Reassess after implementation

Run from the repository root.

```sh
git rev-parse HEAD
rg --files app/src/main
rg -n 'class |fun |sealed |enum |CoroutineScope|launch|async|WebSocket|token|retry|connect|disconnect|Service' app/src/main
```

Read the actual implementation and call sites. Search output alone does not establish correctness.
If the app is still only the greeting, report that no eligible target exists and stop.
Do not invent a state machine or call unit tests formal proof.
When relevant logic exists, use `/create-formal-verification-skill` to replace this document with an executable check for the strongest implemented property.
For event ordering, prefer TLA+ model checking. For input correctness, prefer a verifier of the real code where available.
A possible target, once supported by code, is that stopping presence prevents an older in-flight connection from publishing another update.
A violation could display activity after the user stops sharing it. Choose from actual code rather than assuming this target applies.

## Requirements before claiming verification

Record covered files and functions, sync commit, property, assumptions, bounds, pinned tool version, and one rerun command.
Every modeled atomic step must correspond to an atomic operation in code.
Run the check. Break the guarded property in a scratch copy of the real code and confirm the check catches it.
Restore the scratch change and rerun. Report existing violations without changing product code or weakening properties.
For TLA+ results, use `/statechart`. State what the result excludes.
Advance the sync commit only after confirming the verification still describes the implementation.
Use `/maintain-formal-verification-skill` after relevant changes that did not recheck the property.
