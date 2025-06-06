-keep class android.content.pm.IPackageDataObserver { *; }

-keepclassmembers class eu.darken.butler.common.root.service.RootServiceConnection$Stub$Proxy {
  *;
}
-keepclassmembers class eu.darken.butler.common.adb.AdbServiceConnection** {
  *;
}