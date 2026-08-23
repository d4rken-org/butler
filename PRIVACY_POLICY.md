---
layout: plain
permalink: /privacy
title: "Privacy Policy"
---

# Privacy policy

This is the privacy policy for the Android app "Butler - File Explorer" by Matthias Urhahn (darken).

## Preamble

I do not collect, share or sell personal information.

My underlying privacy principle is the [Golden Rule](https://en.wikipedia.org/wiki/Golden_Rule).

Send a [quick mail](mailto:support@darken.eu) if you have questions.

## Permissions

Details about senstive permissions can be found below.

In general, Butler only processes data locally, on your device. Two features create files that describe what Butler
did in detail, and that you can choose to share:

* If you record a [debug log](#debug-log), the resulting file will contain a detailed log of Butlers actions.
* If Butler crashes, a [crash report](#crash-reports) is written to your device and may contain information about what
  Butler did shortly before the error occured.

### Query installed apps

The `QUERY_ALL_PACKAGES` permission allows Butler to retrieve the inventory of installed apps, i.e. know
which apps you currently have installed on your device.

Butler's app manager is built on it: it lists and searches every installed app, user apps and system apps
alike, and lets you inspect one, export its APK as a file, disable it, clear its cache or uninstall it. The
same inventory provides icons and extra information when you browse directories that belong to an app.

The list of your installed apps is shown inside the app and is not used for analytics or advertising. It
leaves your device only if it ends up in a [debug log](#debug-log) or a [crash report](#crash-reports) that
you choose to share.

### All files access

Butler is a file manager, so browsing and managing the files on your device is its core function. Android restricts
apps to their own directories and to media files unless they hold the `MANAGE_EXTERNAL_STORAGE` permission, which
Android presents as "All files access". Without it Butler cannot show or modify most of your storage.

You grant this permission yourself in the system settings, and you can revoke it there at any time. Butler reads and
writes files only where you direct it to. File names and file contents are not transmitted anywhere.

### Usage access

The `PACKAGE_USAGE_STATS` permission, which Android presents as "Usage access", allows Butler to see which apps are
currently running. Butler uses this to show app related information, for example when displaying installed apps.

This permission is optional. If it is not granted, Butler skips the features that depend on it.

### Install apps

The `REQUEST_INSTALL_PACKAGES` permission allows Butler to hand an APK file you selected to the system's package
installer, so that installing an app you found in your storage works from within Butler.

Butler does not install anything on its own. The system installer is what performs the installation, and it asks for
your confirmation.

## Message of the day

Butler contains a "Message of the day" (MOTD) system that can show the user one-time dismissable messages.
Data for the messages is hosted on GitHub within Butler's respository.
Butler sends HTTP GET requests (similar to visiting a link with a web browser) to GitHub's servers to check for new
MOTDs. A GitHub account is not required.

The MOTD check is optional and can be disabled during onboarding or in the settings.

GitHubs privacy policy can be found here:
https://docs.github.com/site-policy/privacy-policies/github-privacy-statement

## Update check

The
`FOSS` build flavor (i.e. not the Google Play version) of Butler includes an "update check" mechanism that can show a card on the dashboard if a newer version is available.
Butler sends HTTP requests to GitHub`s servers to retrieve the [latest release](https://github.com/d4rken-org/butler/releases/latest) information. A GitHub account is not required.

The update check is optional and can be disabled during onboarding or in the settings.

GitHubs privacy policy can be found here:
https://docs.github.com/site-policy/privacy-policies/github-privacy-statement

## Debug log

The app has a debug log feature that can be used to assist troubleshooting efforts.
This feature creates a log file that contains verbose output of what the app is doing.

It is manually triggered by the user through an option in the app settings.
The recorded log file can be shared through compatible apps (e.g. your email app) using the system's share dialog.
As this log file may contain sensitive information (e.g. details about files or your installed applications) it should only be shared with trusted parties.

## Crash reports

If Butler crashes, it writes a crash report into its own private storage on your device. The report contains the error
details and information about what Butler was doing shortly before the crash.

Crash reports are never sent anywhere automatically. Butler does not include any crash reporting or analytics service.
Reports stay on your device until you open them via "Bug reports" in the app settings and share them yourself using the
system's share dialog.

As a crash report may contain sensitive information (e.g. details about files or your installed applications) it should
only be shared with trusted parties.
