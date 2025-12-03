- How does trash handle SAF based path deletion? (Currently unsupported - only LocalPath)

- Root based deletion moves files where? Same as SAF? (Uses LocalPath with root gateway)

- How do we handle the system deleting our app cache and the trash folder
- System cache deletion handling - DB inconsistency risk (syncWithFileSystem() runs at app init)

- Restore ownership/permissions - Data stored but not restored (TODO at line 179)