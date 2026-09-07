# Exception class names are user-visible (Throwable.localized() labels read "Error - <simpleName>")
# and head every asLog() dump in logs and bug reports. Deliberately every Throwable, not only
# eu.darken.butler.**: library exceptions (smbj, okio, ...) reach localized() the same way.
# Names only: shrinking and optimization stay allowed (-keepnames would forbid optimization).
-keep,allowshrinking,allowoptimization class * extends java.lang.Throwable
