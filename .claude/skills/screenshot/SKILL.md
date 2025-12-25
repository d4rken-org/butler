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

**Fallback:** If no clear context, use a generic descriptive name like
`debug-screen` or let the script use its timestamp default (omit filename parameter).

### 2. Check for Multiple Devices

Before executing the screenshot script, check if multiple devices are connected:

```bash
adb devices
```

**If multiple devices are detected:**

1. Get device information for each device:
   ```bash
   adb -s <device-serial> shell getprop ro.product.model
   ```
2. Use the AskUserQuestion tool to ask which device to use, presenting:
    - Device model name
    - Device serial number
3. When executing the screenshot script, pass the selected device serial using the `-d` flag:
   ```bash
   ./screenshot.sh -d <device-serial> [generated-filename]
   ```

**If only one device is connected:**
Proceed directly to step 3 without the `-d` flag.

### 3. Execute Screenshot Script

Run the screenshot script from this skill directory:

```bash
./screenshot.sh [generated-filename]
# Or with device selection:
./screenshot.sh -d <device-serial> [generated-filename]
```

The script will:

- Detect connected Android devices via ADB
- Handle device selection interactively if multiple devices are connected (fallback if -d not provided)
- Automatically find the project root directory
- Capture the screenshot to `{project-root}/.claude/tmp/[filename].png`
- Display device model and serial information
- Report the saved file path

### 4. Display Screenshot

After successful capture, immediately use the Read tool to display the screenshot image so the user can see it.

### 5. Provide Confirmation

Report back with:

- Device information (model and serial)
- Screenshot file path
- Brief confirmation that the screenshot is being displayed

## Example Interactions

### Single Device Scenario

```
User: "Take a screenshot of the current UI"

Assistant: I'll capture a screenshot from your Android device.

[Checks: adb devices - shows 1 device]
[Executes: ./screenshot.sh ui-current]
[Uses Read tool on: {project-root}/.claude/tmp/ui-current.png]

Screenshot captured from device Pixel 8 (serial: ABC123XYZ) and saved to `{project-root}/.claude/tmp/ui-current.png`.
```

### Multiple Device Scenario

```
User: "Take a screenshot"

Assistant: I'll capture a screenshot from your Android device.

[Checks: adb devices - shows 2 devices]
[Gets device info for each]
[Uses AskUserQuestion to present choices:
  - Pixel 8 (38101FDJH000P9)
  - Emulator (emulator-5554)]

User selects: Pixel 8

[Executes: ./screenshot.sh -d 38101FDJH000P9 debug-screen]
[Uses Read tool on: {project-root}/.claude/tmp/debug-screen.png]

Screenshot captured from device Pixel 8 (serial: 38101FDJH000P9) and saved to `{project-root}/.claude/tmp/debug-screen.png`.
```

## Error Handling

Common issues and resolutions:

- **No devices found**: Verify device is connected via USB and ADB debugging is enabled
- **Multiple devices without -d flag**: The script will prompt interactively, or use step 2 to handle proactively
- **Permission denied**: Check ADB authorization on device
- **Script not found**: Ensure you're running from the correct skill directory path

## Important Notes

- The script automatically detects the project root using `git rev-parse --show-toplevel`
- Screenshots are always saved to `{project-root}/.claude/tmp/` regardless of where the script is executed
- **Always check for multiple devices first** (step 2) to provide a better user experience
- Use the AskUserQuestion tool to let users select devices when multiple are connected
- Always display the screenshot immediately after capture using the Read tool
- Generate meaningful filenames based on conversation context
- Previous screenshots are overwritten if the same filename is used
