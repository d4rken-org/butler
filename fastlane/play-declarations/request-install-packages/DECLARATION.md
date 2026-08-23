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
Butler is a file manager. Users receive APK files through downloads, transfers, backups, and Butler's App Manager can export an installed app as an APK; Butler browses, moves, copies and shares them like any other file. Opening one offers it via ACTION_VIEW and a FileProvider URI through the system chooser, where the user picks Android's package installer. Butler has no installer logic: Android asks the user to allow it as an install source, unless already allowed, then to confirm the install.
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
file and chooses **"Open with"**, Butler builds an `ACTION_VIEW` intent carrying a `FileProvider`
URI and always wraps it in the **system chooser**, so the app that receives the file is picked by
the user, not by Butler. For an APK the chooser offers Android's **package installer**. Every
decision after that belongs to the OS and the user:

- The user picks the **package installer** in the chooser. Butler never targets it directly and
  never sets itself as a default for the handoff.
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

Android's package installer gates the **originating** app through `REQUEST_INSTALL_PACKAGES`
together with the user's per-source "Install unknown apps" setting. That the intent passes through
the chooser does not move the gate: the installer checks the package that started the request,
which is Butler. Without the manifest declaration the installer aborts the request as an
unauthorized source before drawing any UI, and because `startActivity()` still succeeds on
Butler's side the feature fails silently, which is exactly how the missing permission was found.
No narrower permission or public API preserves this direct, user-selected APK-to-system-installer
workflow.

---

## 3. Avoiding the invalid uses

The policy rejects use not tied to core purpose, and use where a less intrusive method would do.
Butler's use is clear of both:

- **Core purpose**: opening user-selected files with the matching system component is the
  definition of a file manager; APK files are no exception. The feature is documented in the
  store listing.
- **No narrower method exists**: the permission is what the OS requires to authorize Butler as an
  install source for the handoff; Butler performs no installation beyond that handoff.
- **User-initiated only**: three separate user decisions gate every install. The user selects the
  APK and chooses "Open with", picks the package installer in the system chooser, and confirms the
  install in Android's own installer. Authorizing Butler as an install source ("Allow from this
  source") is a further one-time OS gate the user grants. Butler never installs anything without
  the user's explicit action.
- **No monetization angle**: no bundled offers, no promoted APKs, no distribution function.
  Butler only opens files the user already has.

Keep the install feature listed in the store description — Google requires the core feature to be
prominently documented in the listing. `fastlane/metadata/android/en-US/full_description.txt`
carries it under the **Apps and APKs** heading:

```
- App manager: browse installed apps, disable them, export APKs, clear app data and uninstall.
- Receive, inspect and open APK files. Installation goes through Android's system installer.
```

Those two lines cover both halves of the permitted use: the first is the App Manager's APK export,
the second the receive-and-open flow. Neither may be dropped while this declaration stands.

---

## 4. Demo video script

Produced by the automated recorder (`./record.sh`); see
[`../README.md`](../README.md#generating-the-demo-videos-automated). Portrait 720×1606, synthetic
data only. The installed app is CapOd (`eu.darken.capod`) — another app by the same developer,
pinned by URL + SHA-256 in `record.sh`, so no third-party brand appears.

The recorder enforces Google's 2-minute cap rather than trusting a nominal length: it times the
recorded flow and aborts at 108s or more, which leaves room for the 3s title card and 3.5s end card
`postprocess.sh` concatenates. An abort leaves the clip on the device unpulled, so the overrunning
run contributes nothing. It does not clear the output directory, so delete any earlier `raw.mp4`
and `declaration.mp4` before re-recording rather than assuming a failed run left none.

Post-process this one with the caption band lifted, or shot 4 loses its evidence:

```
CAPY=300 ./postprocess.sh /tmp/butler-demo/request-install-packages
```

The default band sits where the system chooser draws its app labels, so "Butler" and "Package
installer" end up behind the caption that describes them. `CAPY` defaults to the value the other
declarations use, so lifting it here changes nothing about their videos.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — File Explorer / eu.darken.butler" over "Opening a user-selected APK in Android's package installer (REQUEST_INSTALL_PACKAGES)". |
| 2 | *An APK arrives from another app* | Android's own Files app on `Documents`, where the `.apk` sits. Long-press it → **Share** → **Butler** (a chooser appears only when more than one app handles the share; where Butler is the sole handler the system goes straight to it) → Butler's arrival dialog for the shared file → **Save as…** → the **Saver** opens on it, destination `Download`, the file's own name prefilled → **Save** → once the save has finished, **Open directory**. Butler receives an app package handed over by another app, which is the "sending or receiving app packages" half of the permitted use. |
| 3 | *An APK file in your file explorer* | Butler's Explorer on `Download`, the saved `.apk` visible among normal files. |
| 4 | *Select the APK, choose 'Open with'* | Tap the APK → file options → **"Open with"** → the system chooser offers **Butler** and **Package installer**, and the user picks **Package installer**. Butler offers the file; the user decides who opens it. |
| 5 | *Approve the install source* | The OS blocks the install: "For your security…" → **Settings**. |
| 6 | *Allow APK installs from Butler* | The per-source **"Install unknown apps"** screen for Butler → the user enables **"Allow from this source"**. This is the visible permission grant. |
| 7 | *Confirm in Android's installer* | With the source now allowed, the user reopens the APK: **"Do you want to install this app?"** → taps **Install** → "App installed" → **Done**. |
| 8 | **End card** | "Butler only opens APKs you selected, in Android's installer. Approval and install confirmation stay under your control." |

Shots 2 to 7 are the six burned-in captions, quoted here in italics so the table and the video read
the same; rows 1 and 8 are the title and end cards, which carry no caption. Shot 2 covers the
receiving half of the permitted use as a real inter-app handoff. Shots 5 and 6 are the
policy-critical moment, the permission's user-facing grant, split across the block dialog and the
toggle the user actually flips. Shots 4 to 7 together show the three decisions that gate an install
(which app opens the file, whether Butler may be a source, whether to install), matching the
"user-initiated installation of app packages" half.

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the REQUEST_INSTALL_PACKAGES permission.

1. An APK file sits in the Documents folder of Android's own Files app. The user long-presses it, chooses Share and sends it to Butler. Butler asks what to do with the arriving file and the user picks Save as, which opens Butler's save-as screen on it. The file is saved into Download under its own name, and Open directory shows it there. Butler receives an app package handed over by another app and manages it as a file, which is the first half of the permitted use.
2. The explorer now shows the APK in Download among ordinary files.
3. The user taps the APK and chooses "Open with". Butler offers the file through Android's chooser, which lists Butler itself and the system package installer, and the user picks the package installer. Butler does nothing beyond offering the file.
4. Android blocks the install and asks the user to allow Butler as an install source. The per-source "Install unknown apps" setting is switched on for Butler. This is the permission's user-facing grant and it is a decision only the user can make.
5. The APK is opened again, Android's installer asks "Do you want to install this app?", the user taps Install, and the app is installed.

The app installed in this video is CapOd, another app by the same developer, so no third-party brand appears. Butler has no installer logic of its own: it never targets the installer directly, and it cannot install anything without the user picking the file, choosing who opens it, allowing the install source and confirming in Android's own installer. Everything shown happens on the device.
```

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/12085295>
