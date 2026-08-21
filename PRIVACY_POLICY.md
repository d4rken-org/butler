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

In general, Butler only processes data locally, on your device. Two edge cases exist:

* If you record a [debug log](#debug-log), the resulting file will contain a detailed log of Butlers actions.
* If you enable [automatic error reports](#automatic-error-reports) and an error occurs, the resulting bug report may
  contain information about what Butler did shortly before the error occured.

### Query installed apps

Butler has multiple features that require the `QUERY_ALL_PACKAGES` permission.
The
`QUERY_ALL_PACKAGES` permission allows Butler to retrieve the inventory of installed apps, i.e. know which apps you currently have installed on your device. To display icons and extra information when browsing directories related to an app.

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
