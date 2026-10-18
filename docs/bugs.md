---
title: Reporting problems
description: "Report a Butler bug and record a debug log without posting private information publicly."
permalink: /bugs
translation_key: bugs
lang: en
---
# Reporting problems
{: #reporting-problems }

Check whether a newer version fixes the problem, and look through [existing issues](https://github.com/d4rken-org/butler/issues) before opening a new one. For permission or connection problems, start with [Setup](/setup) and [Troubleshooting](/faq).

## File a report
{: #file-a-report }

[Open a GitHub issue](https://github.com/d4rken-org/butler/issues/new/choose) and include:

- What you expected and what actually happened.
- The steps needed to reproduce the problem.
- Butler's version and whether it came from Google Play or GitHub.
- Your device model and Android version.
- The type of storage involved: device storage, SD card, a storage-provider app or a network server.
- Whether root, Porter or Shizuku was in use, including its version if relevant.
- The exact error message and a screenshot if it helps explain the problem.

GitHub issues are public. Remove private file names, account details, server addresses and other sensitive information from anything you post.

## Record a debug log
{: #debug-log }

A debug log records Butler's actions while you reproduce a problem.

1. Open **Settings**, then **Support**.
2. Under **Other**, open **Bug reports**.
3. Tap **Record**. A recording banner remains visible while you move around Butler.
4. Reproduce the problem.
5. Tap **Stop** in the Bug reports workspace or use the recording banner.
6. Open the finished recording and choose **Share** to export it through Android's share dialog.

Very short recordings ask for confirmation before stopping. Try to include the actual failing action rather than only the error screen.

Recording can resume when Butler restarts. If the issue involves startup or storage detection, starting a recording before restarting Butler can help capture it. Remember to stop the recording afterward.

> Logs can include file paths, installed-app details and other sensitive information. Share them only with trusted people. Do not attach an unreviewed log to a public issue.

To send a log privately, open **Settings**, **Support**, then **Contact developer**. The form emails [support@darken.eu](mailto:support@darken.eu) and can attach a log or record a new one. Include the link to your issue so the report and log can be matched. Use **Share** to export a recording rather than looking for it with a file manager.

## Crash reports
{: #crash-reports }

Butler saves crash reports locally. Open **Bug reports** to inspect and share one. Reports are not sent automatically. The same privacy precautions apply as for debug logs.

See the [privacy policy](/privacy#debug-log) for more about report contents.
