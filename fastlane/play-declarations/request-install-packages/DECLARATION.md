# Declaration — Install packages (`REQUEST_INSTALL_PACKAGES`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **Request install packages**

## 1. Permitted use to select

**File sharing, transfer or management.**

Google lists this verbatim among the REQUEST_INSTALL_PACKAGES permitted uses, and requires that
core functionality includes:

> "Sending or receiving app packages; AND enabling user-initiated installation of app packages."

---

## 2. "How does your app use this permission?" (paste into the form)

Butler is a file manager. Browsing, organizing, and **opening local files — including Android
package files (`.apk`)** — is its core purpose. Users routinely have APK files in their storage
(downloads, file transfers, backups, and APKs exported by Butler's own App Manager) and manage
them with Butler like any other file.

Installing a user-selected APK is core file-manager functionality: when the user selects an APK
file and chooses **"Open with"**, Butler hands the file to the **Android system package
installer** (`ACTION_VIEW` with a `FileProvider` URI). Everything after that handoff is owned by
the OS and the user:

- The first time Butler is used as an install source, Android asks the user to **allow Butler as
  an install source** ("Install unknown apps", a persistent per-source setting).
- The user must **explicitly confirm the installation** in the system installer dialog.

Butler contains no installation logic of its own: it does not use the `PackageInstaller` session
APIs, never installs or updates anything silently or in the background, and cannot install
anything without the user picking a file and confirming in Android's own installer.

Butler **receives and manages** APK packages *and* **enables their user-initiated installation**,
satisfying both halves of the core-functionality requirement. On the package-handling side, APKs
enter the device through downloads, file transfers, backups, and app sharing, and Butler's App
Manager can export an installed app as an APK; Butler browses, moves, shares, and opens them like
any other file. On the installation side, opening one hands the selected APK to Android's system
installer for user-confirmed installation.

Android's package installer gates the originating app through `REQUEST_INSTALL_PACKAGES` together
with the user's per-source "Install unknown apps" setting. Without the manifest declaration, Butler
cannot be authorized as an install source (API 26+), so the request does not proceed and the
feature cannot complete. No narrower permission or public API preserves this direct, user-selected
APK-to-system-installer workflow.

---

## 3. Avoiding the invalid uses

The policy rejects use not tied to core purpose, and use where a less intrusive method would do.
Butler's use is clear of both:

- **Core purpose**: opening user-selected files with the matching system component is the
  definition of a file manager; APK files are no exception. The feature is documented in the
  store listing.
- **No narrower method exists**: the permission is what the OS requires to authorize Butler as an
  install source for the handoff; Butler performs no installation beyond that handoff.
- **User-initiated only**: the user selects the APK, chooses "Open with", and confirms the install
  in Android's own system installer. Authorizing Butler as an install source ("Allow from this
  source") is an additional one-time OS gate the user grants. Butler never installs anything
  without the user's explicit action.
- **No monetization angle**: no bundled offers, no promoted APKs, no distribution function.
  Butler only opens files the user already has.

Keep the install feature listed in the store description
(`fastlane/metadata/android/en-US/full_description.txt` includes:
"Receive, manage, and open APK files - install apps through Android's system installer.") —
Google requires the core feature to be prominently documented in the listing.

---

## 4. Demo video script

Produced by the automated recorder (`./record.sh`); see
[`../README.md`](../README.md#generating-the-demo-videos-automated). Length **~68s** (incl. title
and end cards), portrait 720×1606, synthetic data only. The installed app is CapOd
(`eu.darken.capod`) — another app by the same developer, pinned by URL + SHA-256 in `record.sh`,
so no third-party brand appears.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — Opening a user-selected APK in Android's package installer (REQUEST_INSTALL_PACKAGES)". |
| 2 | **Explorer with an APK** | Butler's Explorer on `Download`, an `.apk` file visible among normal files. |
| 3 | **User action** | Tap the APK → file options → **"Open with"** — Butler hands the file to the system installer. |
| 4 | **Android's source approval** | The OS blocks: "For your security…" → **Settings** → per-source **"Install unknown apps"** screen → user enables **"Allow from this source"** for Butler. This is the visible permission grant. |
| 5 | **User-confirmed install** | With the source now allowed, the user reopens the APK: **"Do you want to install this app?"** → taps **Install** → "App installed" → **Done**. |
| 6 | **End card** | "Butler only opens APKs you selected, in Android's installer. Approval and install confirmation stay under your control." |

Shot 4 is the policy-critical moment — the permission's user-facing grant. Shots 3–5 together
show that all three decisions (file, source, install) belong to the user, matching the
"user-initiated installation of app packages" requirement of the permitted use we selected.

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/12085295>
