# Declaration — Install packages (`REQUEST_INSTALL_PACKAGES`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **Request install packages**

## Play Console form

Two checkbox groups and a video link, in the order below. Sections 1 to 4 are background for
whoever fills the form in, not fields.

### Core purpose

*"Which permitted functionality does your app provide? Select all that apply."*, under the warning
*"Selecting a category that does not match the core purpose of your app may lead to a rejection."*

- [ ] web browsing
- [ ] Search or Assistant
- [ ] communication services that support attachments
- [x] **file sharing, transfer or management**: Butler is a file manager, and APK files are files
      it receives, browses, moves, shares and opens like any other.
- [ ] enterprise device management
- [ ] none of these

The five unticked boxes describe products Butler is not: it is no browser, no search or assistant
surface, no messaging or attachment service, and no device-management tool. Ticking **none of
these** would fail review outright, since it states the app has no permitted use for a permission
it declares.

### Usage

*"Why does your app need to use the REQUEST_INSTALL_PACKAGES permission? Select all that apply."*

- [x] **App functionality**: without the permission the OS will not authorize Butler as an install
      source, so opening an APK cannot proceed.
- [ ] Analytics
- [ ] Developer communications
- [ ] Advertising or marketing
- [ ] Fraud prevention, security, and compliance
- [ ] Personalization
- [ ] Account management

### Video

*"Video instructions"*, a link field taking a YouTube or cloud-storage URL. Because this form has
no text field, the video is the only evidence Google gets, and it has to carry **both** halves of
the permitted use: receiving and managing an app package as a file, and enabling the
user-initiated installation of it. The storyboard in [§4](#4-demo-video-script) and the recorder
are built around that; hosting rules are in [`../README.md`](../README.md#demo-videos).

### This form has no free-text field

There is nowhere on this form to paste a written rationale. Sections 2 and 3 exist for reviewers of
this repository and for a follow-up exchange with Google, not for the console.

---

## If Google asks a follow-up

Not a console field. This is §2 condensed to 500 characters for a policy email or an appeal reply,
so the written answer matches the form and the video:

```text
Butler is a file manager. Users receive APK files through downloads, transfers and backups, and Butler's App Manager can export an installed app as an APK; Butler browses, moves, copies and shares them like any other file. When the user opens one, Butler hands it to Android's system package installer with ACTION_VIEW and a FileProvider URI. Butler has no installer logic of its own: Android asks the user to allow Butler as an install source, then to confirm the install itself.
```

---

## 1. Permitted use to select

**File sharing, transfer or management.**

Google lists this verbatim among the REQUEST_INSTALL_PACKAGES permitted uses, and requires that
core functionality includes:

> "Sending or receiving app packages; AND enabling user-initiated installation of app packages."

---

## 2. "How does your app use this permission?" (reference, not a form field)

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
[`../README.md`](../README.md#generating-the-demo-videos-automated). Length **~100s** (incl. title
and end cards), portrait 720×1606, synthetic data only. The installed app is CapOd
(`eu.darken.capod`) — another app by the same developer, pinned by URL + SHA-256 in `record.sh`,
so no third-party brand appears.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — Opening a user-selected APK in Android's package installer (REQUEST_INSTALL_PACKAGES)". |
| 2 | **An APK arrives from another app** | Android's own Files app on `Documents`, where the `.apk` sits. Long-press it → **Share** → pick **Butler** in the chooser → Butler's arrival dialog for the shared file → **Save as…** → the **Saver** opens on it, destination `Download`, the file's own name prefilled → **Save** → once the save has finished, **Open directory**. Butler receives an app package handed over by another app, which is the "sending or receiving app packages" half of the permitted use. |
| 3 | **Explorer with an APK** | Butler's Explorer on `Download`, the saved `.apk` visible among normal files. |
| 4 | **User action** | Tap the APK → file options → **"Open with"**: Butler hands the file to the system installer. |
| 5 | **Android's source approval** | The OS blocks: "For your security…" → **Settings** → per-source **"Install unknown apps"** screen → user enables **"Allow from this source"** for Butler. This is the visible permission grant. |
| 6 | **User-confirmed install** | With the source now allowed, the user reopens the APK: **"Do you want to install this app?"** → taps **Install** → "App installed" → **Done**. |
| 7 | **End card** | "Butler only opens APKs you selected, in Android's installer. Approval and install confirmation stay under your control." |

Shot 2 covers the receiving half of the permitted use as a real inter-app handoff, which the
recorder used to do off camera with an `adb push`. Shot 5 is the policy-critical moment, the
permission's user-facing grant. Shots 4 to 6 together show that all three decisions (file, source, install) belong to the
user, matching the "user-initiated installation of app packages" half.

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the REQUEST_INSTALL_PACKAGES permission.

1. An APK file sits in the Documents folder of Android's own Files app. The user long-presses it, chooses Share and picks Butler. Butler asks what to do with the arriving file and the user picks Save as, which opens Butler's save-as screen on it. The file is saved into Download under its own name, and Open directory shows it there. Butler receives an app package handed over by another app and manages it as a file, which is the first half of the permitted use.
2. The explorer now shows the APK in Download among ordinary files.
3. The user taps the APK and chooses "Open with". Butler hands the file to Android's system package installer and does nothing else.
4. Android blocks the install and asks the user to allow Butler as an install source. The per-source "Install unknown apps" setting is switched on for Butler. This is the permission's user-facing grant and it is a decision only the user can make.
5. The APK is opened again, Android's installer asks "Do you want to install this app?", the user taps Install, and the app is installed.

The app installed in this video is CapOd, another app by the same developer, so no third-party brand appears. Butler has no installer logic of its own: it cannot install anything without the user picking the file, allowing the install source and confirming in Android's own installer. Everything shown happens on the device.
```

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/12085295>
