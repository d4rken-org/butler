# Declaration — Query all packages (`QUERY_ALL_PACKAGES`)

App: **Butler – File Explorer** (`eu.darken.butler`)
Form: Play Console → App content → Permissions declaration form → **Query all packages**

## 1. Permitted use to select

**File managers.**

Google lists this verbatim among the QUERY_ALL_PACKAGES permitted uses:

> "Permitted uses include device search, antivirus apps, file managers, and browsers."

---

## 2. "How does your app use this permission?" (paste into the form)

Butler is a file manager whose **App Manager** is a prominent, core user-facing feature. Its
purpose is to let the user **search for and browse every installed application** — user and
system — on the device, then inspect and manage any of them:

- **Search and list all installed apps** (user and system) by name or package id.
- Inspect an app in depth: package id, version, target/min SDK, install metadata, on-device
  storage paths, and exported components (activities, services, receivers).
- **Export an app's APK file** to shared storage — where it immediately appears in Butler's own
  file explorer.
- Disable/enable, clear cache, and uninstall apps.

The feature requires broad package visibility (`QUERY_ALL_PACKAGES`) because its core purpose is
to **search across all installed packages** so the user can find and manage *any* app — Google's
named "file managers" permitted use, which matches the "core purpose to search for all apps"
criterion. A declared `<queries>` manifest reveals only a predetermined subset and cannot back a
user-facing "search all installed apps" feature, so no less-broad method suffices.

Exporting an APK ties the App Manager directly to Butler's file-manager identity: the exported
`.apk` is written to shared storage and is then a file the user browses, moves, and shares in
Butler like any other. The installed-app inventory is shown **only inside the app**, on-device;
it is never sold, shared, transmitted, or used for analytics or advertising. (See the privacy
policy.)

---

## 3. Avoiding the invalid uses

The QUERY_ALL_PACKAGES policy rejects use that is not tied to core purpose, or where data is
acquired for sale/analytics/ads, or where a less-broad method would do. Butler's use is clear of
all three:

- **Core purpose**: the App Manager is a primary, documented feature of the app.
- **No monetization of inventory**: package data never leaves the device and is not shared.
- **No narrower method suffices**: a user-facing "manage any installed app" feature inherently
  needs full visibility.

Keep the App Manager feature listed in the store description
(`fastlane/metadata/android/en-US/full_description.txt` already includes:
"App manager - browse, disable, export APKs, clear cache, uninstall.") — Google requires the
core feature to be prominently documented in the listing.

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
| 4 | **Inspect an app in depth** | Open an app's detail: package id, version, SDK levels, storage paths, exported components. |
| 5 | **Export the APK into the file explorer** | From the detail, **Export APK** → a "Save as" file-manager workspace (destination prefilled to `Download`) → **Save** → "1 file saved" → **Open directory** → the Explorer opens on `Download` with the exported `.apk` now present as a file. |
| 6 | **End card** | "Searches and manages every installed app on the device. Package data stays on-device and is never shared." |

The breadth header and the **search-all-apps** step are the policy-critical shots — they show why
broad visibility into installed packages is required. Shot 5 then ties QUERY_ALL_PACKAGES directly
to the **"File managers"** permitted use we selected: the exported APK lands in Butler's own file
explorer, where the user browses, moves, and shares it like any other file (as described in §2).

---

Policy reference: <https://support.google.com/googleplay/android-developer/answer/10158779>
