- How does trash handle SAF based path deletion? (Currently unsupported - only LocalPath)

- Root based deletion moves files where? Same as SAF? (Uses LocalPath with root gateway)

- How do we handle the system deleting our app cache and the trash folder
- System cache deletion handling - DB inconsistency risk (syncWithFileSystem() runs at app init)

- [DONE] Max size enforcement - cleanupBySize() called after moveToTrash(), deletes oldest items to 80% of limit
- Restore ownership/permissions - Data stored but not restored (TODO at line 179)

- Trash operations should provide trash specific progress messages
- Trash operations have a "T" trash item summary entry for each trashed item.