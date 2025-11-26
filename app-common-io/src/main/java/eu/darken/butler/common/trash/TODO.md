- Trash needs multi select and restore/delete
- Trash path is not displayed correct, neither is the name
- Trash should show when the file was deleted
- Trash size should be displayed, besides the count at device level

- When is TrashCleanupScheduler.cancel() called? If disable it via settings, does cancel get called? Do we clear the trash?

- Check if we are using all of the added strings or if there are unused strings


- How does trash handle SAF based path deletion?
- Shouldn't TrashRepository be named "Repo"?
- Shouldn't TrashRepository delete based via ID and not construct the whole item?

- Root based deletion moves files where? Same as SAF?
- TrashItem shouldn't use string for path type.
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
