# AegisAC 1.0.0

A layered anti-cheat plugin targeting **Paper 1.21.11 / Java 21**.

## Checks

### Movement
- **Speed** — movement-attribute-aware horizontal speed limits, slipperiness allowance, low-TPS allowance, velocity grace.
- **Fly** — excessive vertical rise + sustained hover detection.
- **NoClip** — detects movement into full occluding blocks.
- **Timer** — detects abnormally high movement-update rates.

### Combat
- **Reach** — measures eye position to the nearest point of the target hitbox and respects the 1.21 interaction-range attribute.
- **KillAura** — impossible backwards hits and rapid multi-target switching.
- **AutoClicker** — CPS ceiling plus low-variance click-pattern detection using Paper arm-swing events.

### Automation / world interaction
- **FastBreak** — repeated implausibly fast breaks of non-trivial blocks.
- **Nuker** — extreme block-break burst detection.
- **Scaffold** — high-speed below-feet placement with incompatible look direction.

### Knockback
- **AntiKB** — records combat velocity, measures the player's actual displacement a short time later, ignores common movement/collision exemptions, and can reapply knockback after repeated failures.

### X-Ray
AegisAC adds a **heuristic mining detector** for repeated low-exposure valuable-ore finds. It deliberately does not auto-cancel mining or auto-ban for X-Ray, because mining statistics alone can produce false positives.

For actual X-Ray prevention, use **Paper's built-in Anti-Xray** too. A ready-to-merge example is included as `paper-antixray-example.yml`.

## Commands

- `/ac status` — enabled checks + TPS
- `/ac alerts` — toggle live staff alerts
- `/ac vl <player>` — view violation levels
- `/ac reset <player>` — reset violation levels
- `/ac reload` — reload `config.yml`

Permissions:
- `aegisac.command` (default OP)
- `aegisac.alerts` (default OP)
- `aegisac.bypass` (default false)

## Installation

1. Build the project with Java 21.
2. Put `AegisAC-1.0.0.jar` in the server's `plugins/` folder.
3. Start/restart Paper.
4. Tune `plugins/AegisAC/config.yml` for your server before enabling automatic punishment commands.
5. Enable Paper's native Anti-Xray using the included example as a reference.

## Building locally

```bash
mvn package
```

The output is:

```text
target/AegisAC-1.0.0.jar
```

## Building with GitHub Actions

This project includes `.github/workflows/build.yml`. Push the project to GitHub, open the **Actions** tab, run **Build AegisAC**, then download the `AegisAC-1.0.0` artifact.

This is especially useful if your local/school network blocks the Paper Maven repository.

## Tuning notes

- Automatic punishment commands are **off by default**. Cancel/setback/knockback correction still work.
- Start by watching alerts for normal players, then adjust thresholds.
- Servers with custom movement items, launch pads, unusual attributes, or minigame mechanics should add exemptions or loosen the relevant check.
- No server-side anti-cheat can literally detect every private client or guarantee zero false positives. A layered system plus Paper's packet-level Anti-Xray is much stronger than one aggressive heuristic.
