# Declaration — Query all packages (`QUERY_ALL_PACKAGES`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **Query all packages**

## Play Console form

The live form has one free-text box capped at **500 characters**, a checkbox group, and a video
link field. There is no second box for the alternative-API argument, so the one block below has to
carry both the feature and the reason a narrower method fails. Sections 1 to 4 are background, not
fields.

### Core purpose (500 characters)

Prompt: *"Describe 1 feature in your app that requires a permitted use of the QUERY_ALL_PACKAGES
permission."*, with the examples *Device search / Antivirus apps / File managers and browsers* and
the helper line *"Approval will be granted for your entire app, not just for this feature"*.

```text
Butler's App Manager lets the user search every installed app, user and system, then inspect one, export or share its APK, disable it, clear its data or uninstall it. A queries manifest entry only exposes packages named at build time or apps matching declared intents and providers, so it cannot enumerate every installed app for an open search. The inventory is not collected and not used for analytics or advertising; it leaves the device only in an APK or diagnostic report the user shares.
```

### Usage

*"Why does your app need to use the QUERY_ALL_PACKAGES permission? Select all that apply."*

- [x] **App functionality**: the App Manager is the feature, and it cannot list what it cannot see.
- [ ] Analytics
- [ ] Developer communications
- [ ] Advertising or marketing
- [ ] Fraud prevention, security, and compliance
- [ ] Personalization
- [ ] Account management

Analytics and Advertising or marketing must stay unticked in particular: ticking either would
assert exactly the use the QUERY_ALL_PACKAGES policy lists as invalid, acquiring the installed-app
inventory for analytics or ads. The other four describe things Butler does not do with package
data at all.

### Video

*"Video instructions"*, a link field taking a YouTube or cloud-storage URL, 90 seconds or shorter
recommended. The storyboard and the description to paste with it are in
[§4](#4-demo-video-script); hosting rules are in [`../README.md`](../README.md#demo-videos).

---

## 1. Permitted use to select

**File managers.**

Google lists this verbatim among the QUERY_ALL_PACKAGES permitted uses:

> "Permitted uses include device search, antivirus apps, file managers, and browsers."

---

## 2. "How does your app use this permission?" (reference, not a form field)

Butler is a file manager whose **App Manager** is a prominent, core user-facing feature. Its
purpose is to let the user **search for and browse every installed application** — user and
system — on the device, then inspect and manage any of them:

- **Search and list all installed apps** (user and system) by name or package id.
- Inspect an app in depth: package id, version, target/min SDK, install metadata, on-device
  storage paths, and exported components (activities, services, receivers).
- **Export an app's APK file** to shared storage — where it immediately appears in Butler's own
  file explorer — or share it straight to another app.
- Disable/enable, clear an app's data, and uninstall apps.

The feature requires broad package visibility (`QUERY_ALL_PACKAGES`) because its core purpose is
to **search across all installed packages** so the user can find and manage *any* app — Google's
named "file managers" permitted use, which matches the "core purpose to search for all apps"
criterion. A declared `<queries>` manifest element exposes only the packages named at build time
plus the apps that match the intent filters and provider authorities it declares, so it can never
enumerate the installed apps a user-facing "search all installed apps" feature has to offer. No
less-broad method suffices.

Exporting an APK ties the App Manager directly to Butler's file-manager identity: the exported
`.apk` is written to shared storage and is then a file the user browses, moves, and shares in
Butler like any other. The installed-app inventory is not collected and is not used for analytics
or advertising; it leaves the device only inside an APK or a diagnostic report the user chooses to
share. (See the privacy policy.)

---

## 3. Avoiding the invalid uses

The QUERY_ALL_PACKAGES policy rejects use that is not tied to core purpose, or where data is
acquired for sale/analytics/ads, or where a less-broad method would do. Butler's use is clear of
all three:

- **Core purpose**: the App Manager is a primary, documented feature of the app.
- **No monetization of inventory**: the package list is not collected and not used for analytics
  or advertising.
- **No narrower method suffices**: a user-facing "manage any installed app" feature inherently
  needs full visibility.

The live form has no "why is an alternative not sufficient" box, so this argument survives on the
form only in the condensed sentence inside the Core purpose block above. Keep the two in sync when
either changes.

Keep the App Manager feature listed in the store description
(`fastlane/metadata/android/en-US/full_description.txt` already includes:
"App manager: browse installed apps, disable them, export APKs, clear app data and uninstall.") —
Google requires the core feature to be prominently documented in the listing.

---

## 4. Demo video script

Produced by the automated recorder (`./record.sh`); see
[`../README.md`](../README.md#generating-the-demo-videos-automated). Length **~50s**, portrait
720×1606, synthetic data only.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Butler — eu.darken.butler — App manager (QUERY_ALL_PACKAGES)". |
| 2 | **Open the App Manager** | The Apps workspace, with a header showing the installed-app counts ("N user · N system apps"). |
| 3 | **Search across all apps** | Type into the app search field; the list filters across the full installed-package set. |
| 4 | **Inspect an app in depth** | Open an app's detail and scroll it: package id, version, install metadata and SDK levels. |
| 5 | **Export the APK into the file explorer** | From the detail, **Export APK** → a "Save as" file-manager workspace (destination prefilled to `Download`) → **Save** → "1 file saved" → **Open directory** → the Explorer opens on `Download` with the exported `.apk` now present as a file. |
| 6 | **End card** | "Searches and manages every installed app on the device. Not collected, not used for analytics or advertising." |

The breadth header and the **search-all-apps** step are the policy-critical shots — they show why
broad visibility into installed packages is required. Shot 5 then ties QUERY_ALL_PACKAGES directly
to the **"File managers"** permitted use we selected: the exported APK lands in Butler's own file
explorer, where the user browses, moves, and shares it like any other file (as described in §2).

### Video description (paste into YouTube)

```text
Butler, a file explorer for Android, package name eu.darken.butler. This video demonstrates the QUERY_ALL_PACKAGES permission.

1. Butler's App Manager opens. Its header counts the installed apps it can see, user apps and system apps.
2. A search across every installed app filters the list as the query is typed. Searching all installed apps is the feature that needs broad package visibility; a queries manifest entry only exposes packages named at build time or apps matching declared intents and providers, so it cannot enumerate the device.
3. One app is opened in detail, showing its package id, version, install metadata and SDK levels.
4. Its APK is exported through Butler's own save-as workspace into the Download folder.
5. The explorer opens on Download, where the exported APK is now an ordinary file the user can browse, move and share. This is the link between the App Manager and Butler's file-manager core purpose.

From the same detail screen the user can share the APK, disable the app, clear its data or uninstall it. The installed-app list is not collected and is not used for analytics or advertising; it leaves the device only inside an APK or a diagnostic report the user chooses to share.
```

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/10158779>
