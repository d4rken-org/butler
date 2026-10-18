---
title: Working with files
description: "Copy, move, delete and restore files in Butler, and understand storage limits."
permalink: /files
translation_key: files
lang: en
---
# Working with files
{: #working-with-files }

Use an **Explorer** tab to browse folders and manage files. The actions offered depend on the selected items and the storage location's capabilities.

## Select files
{: #select-files }

Press and hold a file or folder to start a selection, then tap other items to add them. The action bar shows actions for the selection. File options provide additional actions for an individual item.

Check the selection before a destructive action, especially when working across several tabs.

## Copy and move
{: #copy-and-move }

1. Select the files or folders in the source location.
2. Choose **Copy** to keep the originals, or **Cut** to move them.
3. Navigate to the destination, in the same Explorer or another tab.
4. Paste the items and review any conflict or permission prompts.

Two Explorer tabs in separate panes can make it easier to keep the source and destination visible. See [Tabs and panes](/getting-started#tabs-and-panes).

Transfers may need access to both locations. Network storage and storage supplied by another app can have different capabilities from local storage. If a transfer fails, inspect its result before retrying; some files may already have been processed.

## Follow an operation
{: #follow-an-operation }

The operations bar shows work in progress. Open an operation for more detail, or use **History** to inspect its result afterward. Read reported problems rather than assuming every selected file was processed.

Canceling an operation stops further work; it does not undo work that has already completed. Keep the source storage and any network connection available until the operation finishes.

## Delete and restore
{: #trash }

Butler has its own trash, enabled by default. Review its settings under **Settings**, **Explorer**. There are limits on how long items stay and how much space trash can use.

When trash is enabled and the location supports it, deletion moves items to trash instead of immediately removing them permanently. If Butler cannot use trash for an item, read the prompt before agreeing to permanent deletion.

To restore an item, open **Trash** from Explorer's home, select it and choose **Restore**. Butler restores it to its original location, which must still be available and writable. Resolve any name conflicts shown by the app.

> Trash is not a backup. Butler uses cache storage for trashed files, and Android may clear it when space is low. Expiration, emptying trash, clearing app data or uninstalling Butler can also remove your recovery option.

Moving a file to trash does not free its storage space yet. Permanently deleting it from trash does. Files deleted permanently cannot be recovered through Butler.

## Search and edit
{: #search-and-edit }

Create a **Search** tab to find files. Select the locations you want to search and use filters to narrow the results. Filename matching and searching inside files are different tasks; check your search options. Regex is available for more specific patterns.

Open text in **Editor** when you want to change it. Save your changes before leaving, and keep a separate copy of important files before editing. Having permission to read a file does not necessarily give permission to save it.

## Network locations
{: #network-storage }

Butler supports SMB shares and SFTP servers. Add a network location from Explorer and enter the server details supplied by its administrator. Adding and testing a location is free; browsing it requires Pro.

- **SMB** is commonly used by Windows shares and NAS devices.
- **SFTP** uses SSH. Check the server's host-key fingerprint with its administrator before trusting a new or changed key.

The device must be able to reach the server, for example through the same network or an appropriate VPN. Butler does not make a private server reachable from the internet. Do not expose a server publicly just to fix a connection problem.

Keep passwords and private keys out of screenshots, public issues and shared logs. If access fails, check the address, credentials, server permissions and network connection.

## When a folder will not open
{: #when-a-folder-will-not-open }

Start with [Setup & permissions](/setup). All files access, a system-folder grant and elevated access are different mechanisms; granting one does not automatically unlock every location.

If the issue persists, collect the folder path, operation and exact error for a [bug report](/bugs). Redact personal names or other private path details before posting publicly.
