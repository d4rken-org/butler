- When is TrashCleanupScheduler.cancel() called? If disable it via settings, does cancel get called? Do we clear the trash?

- How does trash handle SAF based path deletion?

- Root based deletion moves files where? Same as SAF?
- TrashItemOptionsBottomSheet should show when the file was deleted.
- How do we handle the system deleting our app cache and the trash folder
- Can there be name collision when deleting files with the same name from multiple locations?

- Show nested items in trash, browsable.

- Max size enforcement - Setting exists (500MB default) but not enforced
- Restore ownership/permissions - Data stored but not restored (TODO at line 179)
- System cache deletion handling - DB inconsistency risk
- Browsable nested items - Flat list only
- TrashLocationLoader inject workspace id

- Trash operations should provide trash specific progress messages
- Trash operations have a "T" trash item summary entry for each trashed item.
