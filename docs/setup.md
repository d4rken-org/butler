---
title: Setup & permissions
description: "Understand Butler's storage permissions, optional app access, Porter, Shizuku and root."
permalink: /setup
translation_key: setup
lang: en
---
# Setup & permissions
{: #setup--permissions }

Open **Settings**, then **Permission setup**, to review Butler's access. You do not have to enable everything. Butler asks for access when a task needs it, and the available choices depend on your Android version and device.

For ordinary file management, start with storage access. Root and ADB access are optional, not prerequisites.

## Storage permissions
{: #storage-permissions }

Android's **All files access** permission lets Butler manage shared storage, including common folders such as Downloads and Documents. Follow the storage card's action to open the relevant Android settings screen, allow access, then return to Butler.

“All files” does not mean every file on the device. Android still protects app-private data and some system locations. Restrictions on `Android/data` and `Android/obb` depend on the Android version, system file picker and device software. Do not assume a permission that worked on an older device can unlock the same folder on a newer one.

If a folder is inaccessible, use the access methods Butler offers for that location. Even elevated access cannot guarantee that every path will be available on every device.

### Storage Access Framework
{: #saf }

The Storage Access Framework (SAF) is Android's system for letting you select a folder or storage provider and grant an app access to it. It is useful for storage that cannot be reached through ordinary file access, including storage exposed by another app.

When Butler opens the system folder picker:

1. Navigate to the folder or provider you want to use.
2. Choose **Use this folder** and confirm access, if Android allows it.
3. Return to Butler and try the location again.

A disabled **Use this folder** button is a system restriction, not a Butler permission switch. The picker may not offer protected locations at all. Do not disable or remove Android's system Files/Documents app to try to bypass this; Butler relies on it for the picker.

If a provider is missing or a previously granted location stops working, check that the provider app is installed, enabled and signed in. You may need to grant the location again.

## Usage statistics
{: #usage-statistics }

Usage access lets Butler show app storage sizes and estimate which apps were recently active. It is not needed just to browse ordinary files.

Open the usage-access settings from Butler's setup card, select Butler and enable access if you want those features. Device manufacturers may name or arrange this screen differently.

## Notifications
{: #notifications }

Notification permission lets Butler post notifications. Allow it if you want to see notifications for work happening outside the current screen. Background restrictions vary by device; granting notifications alone does not override battery restrictions.

## App inventory
{: #app-inventory }

Butler uses Android's installed-app inventory to populate the Apps tool and show information about files belonging to apps. The relevant permission is `QUERY_ALL_PACKAGES`.

This access is normally available automatically. Some device software adds a separate app-list permission or can revoke it. If Butler reports that the inventory is unavailable, follow the setup card's instructions and check the device's permission settings for Butler.

See the [privacy policy](/privacy#query-installed-apps) for how app information is used.

## ADB access with Porter or Shizuku
{: #adb-access }

[Porter](https://porter.darken.eu/) and [Shizuku](https://shizuku.rikka.app/) let compatible apps perform supported actions with elevated privileges. When started through debugging, they provide Android shell-level access, not unrestricted root access.

Butler supports both and prefers Porter when it is available. You do not need to install both.

1. Install and start your chosen service. Follow [Porter's setup guide](https://porter.darken.eu/setup) or [Shizuku's setup guide](https://shizuku.rikka.app/guide/setup/).
2. Return to Butler's **Permission setup** screen and enable ADB access.
3. Approve the service's permission request for Butler.
4. Check that Butler's setup card reports the connection is ready.

The service may need to be started again after a reboot. If Butler is waiting for a connection, check the service app first, then confirm that Butler is authorized there.

Wireless debugging, pairing and manufacturer-specific startup instructions belong in the service's own guide. They can change independently of Butler.

## Root access
{: #root-access }

Root gives Butler broad access to the device. Enable it only if your device is already rooted and you understand the consequences. Butler does not root the device for you.

Use the root setup card and approve Butler in your root manager. If access is denied, check the root manager's permission list.

Root access can expose system files and app-private data. Deleting or changing those files can break apps or the operating system. Keep backups and do not change files solely because Butler can reach them.

## Still blocked?
{: #still-blocked }

Note the exact folder, operation and error message. Check [FAQ & troubleshooting](/faq), or [record a debug log](/bugs#debug-log) if you can reproduce the problem.
