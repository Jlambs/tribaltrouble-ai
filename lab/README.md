# lab: each AI's own tools and notes

`lab/<name>/` belongs to the AI in `tt/src/main/java/com/oddlabs/tt/player/<name>/` and is committed on that AI's
branch with it: the analyses, scripts, experiments and notes written while developing it, kept as a record of how the
AI was made.

It is a scratchpad. Nothing checks, formats or compiles it, and the game never sees it: the AI must play without it.
Anything the AI runs during a game belongs in its package, and results a tool worked out go into the AI as constants
or param defaults.

```bash
./aisim.sh lab lab/starter/FirstArmory.java RUN      # a Java tool, run from source on the last build
```

How to write tools and what they can read: [docs/aisim.md, Your own tools](../docs/aisim.md#your-own-tools).
