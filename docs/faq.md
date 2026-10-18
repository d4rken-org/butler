---
title: FAQ & troubleshooting
description: "Answers about Butler's permissions, upgrades, inaccessible folders and file operations."
permalink: /faq
translation_key: faq
lang: en
---
# FAQ & troubleshooting
{: #faq--troubleshooting }

## Do I need root?
{: #do-i-need-root }

No. Butler can manage ordinary shared files without root. Android protects some locations and app actions; those may need extra access or remain unavailable. See [Setup & permissions](/setup).

## Why is Android/data still inaccessible?
{: #why-is-androiddata-still-inaccessible }

Android's all-files permission does not grant unrestricted access to other apps' data. The available methods depend on your Android version and device software. Use the options Butler offers for the location, and see [Storage permissions](/setup#storage-permissions).

A system folder picker refusing a folder is not fixed by repeatedly granting the same permission.

## Why is Butler waiting for Porter or Shizuku?
{: #why-is-butler-waiting-for-porter-or-shizuku }

Check that the service is running in the Porter or Shizuku app and that Butler is authorized. A debugging-based service may need to be started again after rebooting. Then return to Butler's **Permission setup** screen. See [ADB access](/setup#adb-access).

## Why can I read a file but not change it?
{: #why-can-i-read-a-file-but-not-change-it }

Read and write permissions are separate. The location may be read-only, the server may restrict your account, or a storage provider may not support the operation. Check the exact error and the permissions for both the source and destination.

## Why did deleting files not free space?
{: #why-did-deleting-files-not-free-space }

Files moved to trash still occupy storage. Inspect **Trash** in Explorer and permanently delete only the items you no longer need. See [Delete and restore](/files#trash) before emptying it.

## Can I recover a deleted file?
{: #can-i-recover-a-deleted-file }

Only if it is still available in Butler's trash. Permanently deleted items, expired items and files deleted where trash is not available cannot be restored through Butler. Trash is not a substitute for a backup.

## A copy or move stopped. Should I repeat it?
{: #a-copy-or-move-stopped-should-i-repeat-it }

Inspect the operation's details or **History** first. An operation can process some items before encountering a problem. Check both locations before retrying, and review overwrite prompts carefully. Canceling does not roll back completed work.

## Why can I add a network location but not browse it?
{: #why-can-i-add-a-network-location-but-not-browse-it }

Adding and testing network locations is free; browsing them requires Butler Pro. A successful connection test also does not guarantee that your server account can read or write every folder. See [Network locations](/files#network-storage).

## Versions and upgrades
{: #versions-and-upgrades }

### Where should I download Butler?
{: #where-should-i-download-butler }

Use [Google Play](https://play.google.com/store/apps/details?id=eu.darken.butler) or [GitHub releases](https://github.com/d4rken-org/butler/releases). The [open testing program](https://play.google.com/apps/testing/eu.darken.butler) provides Play beta builds. Butler is currently in beta; GitHub versions may be marked as pre-releases.

See [Verify downloads](/security) for signing certificates. Do not uninstall your existing app to resolve a signing mismatch without first considering the app data and settings you would lose.

### What is Butler Pro?
{: #what-is-butler-pro }

Butler has no ads. Pro upgrades and sponsorship help fund development. Some advanced features require Pro; the app's upgrade screen shows the current offering. Feature availability can change while Butler is in development.

The Google Play version uses Google Play for purchases. If you already purchased Pro, check that you are using the purchasing account and use the app's restore option.

### How can I upgrade without Google Play?
{: #how-can-i-upgrade-without-google-play }

The FOSS version available on GitHub offers a sponsorship-based upgrade flow instead of Google Play billing. Open the upgrade screen in that version and follow its sponsorship instructions. A Play purchase and the FOSS sponsorship flow are different mechanisms.

You can also [sponsor development directly](https://github.com/sponsors/d4rken).

## Does Butler send my files or debug logs automatically?
{: #does-butler-send-my-files-or-debug-logs-automatically }

Butler has no tracking or automatic crash-report submission. Debug and crash reports stay on the device unless you choose to share them. Optional messages contact GitHub, and so do update checks in the GitHub version; see the [privacy policy](/privacy) for details.

Using a network location or sharing a file explicitly sends data to the server or app you selected. Debug logs may contain file paths and installed-app details, so share them only with someone you trust.

## Where can I ask a question?
{: #where-can-i-ask-a-question }

Use [GitHub Discussions](https://github.com/d4rken-org/butler/discussions) or [Discord](https://discord.gg/ktMbDBAp4K). For a reproducible problem, see [Reporting problems](/bugs).
