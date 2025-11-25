- Recycle bin needs multi select and restore/delete
- Recycle bin path is not displayed correct, neither is the name
- Recycle bin should show when the file was deleted
- Recycle bin size should be displayed, besides the count at device level

- When is RecycleBinCleanupScheduler.cancel() called? If disable it via settings, does cancel get called? Do we clear the recycle bin?
- Store settings need on click actions to change trash max age and trash max size

- Check if we are using all of the added strings or if there are unused strings


- How does recycle bin handle SAF based path deletion?
- Shouldn't RecycleBinRepository be named "Repo"?
- Shouldn't RecycleBinRepository delete based via ID and not construct the whole item?

- Root based deletion moves files where? Same as SAF?
- RecycleBinItem shouldn't use string for path type.
- RecycleBinItemOptionsBottomSheet should show when the file was deleted.
- How do we handle the system deleting our app cache and the recycle bin folder
- Can there be name collision when deleting files with the same name from ultiple locations?

- Show nested items in recycle bin, browsable.

- Max size enforcement - Setting exists (500MB default) but not enforced
- Restore ownership/permissions - Data stored but not restored (TODO at line 179)
- System cache deletion handling - DB inconsistency risk
- Browsable nested items - Flat list only