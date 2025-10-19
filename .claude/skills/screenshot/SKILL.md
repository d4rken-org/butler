---
name: ADB Screenshot Capture
description: Captures screenshots from Android devices via ADB when user explicitly requests a screenshot, screen capture, or screencap
---

# ADB Screenshot Capture Skill

This skill captures screenshots from Android devices connected via ADB and automatically displays them for analysis.

## When to Use

Invoke this skill ONLY when the user explicitly requests:
- "take a screenshot"
- "capture the screen"
- "screencap"
- "get a screenshot"
- "show me the current screen"

Do NOT invoke automatically or proactively. Wait for explicit user request.

## Workflow

### 1. Generate Context-Based Filename

Analyze the conversation context to create a descriptive filename:

**Good examples:**
- If discussing UI layout issues → `ui-layout-issue`
- If debugging navigation → `navigation-bug`
- If reviewing settings screen → `settings-screen`
- If checking badge positioning → `badge-position`
- If verifying after a fix → `fix-verification`

**Fallback:** If no clear context, use a generic descriptive name like `debug-screen` or let the script use its timestamp default (omit filename parameter).

### 2. Execute Screenshot Script

Run the screenshot script from this skill directory:

```bash
./screenshot.sh [generated-filename]
```

The script will:
- Detect connected Android devices via ADB
- Handle device selection interactively if multiple devices are connected
- Capture the screenshot to `.claude/tmp/[filename].png`
- Display device model and serial information
- Report the saved file path

### 3. Display Screenshot

After successful capture, immediately use the Read tool to display the screenshot image so the user can see it.

### 4. Provide Confirmation

Report back with:
- Device information (model and serial)
- Screenshot file path
- Brief confirmation that the screenshot is being displayed

## Example Interaction

```
User: "Take a screenshot of the current UI"