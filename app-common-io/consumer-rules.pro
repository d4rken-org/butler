# IBinder.getInterface() (app-common BinderExtensions.kt) resolves these AIDL interfaces by name:
# Class.forName("<iface>$Stub").getField("DESCRIPTOR") (declared on the interface, reached through
# Stub's superinterface) and Class.forName("<iface>$Stub$Proxy").getDeclaredConstructor(IBinder).
# Generated code, so @Keep is not an option. -keepclassmembers would keep the members but let R8
# rename the classes, which breaks the string lookup.
-keep class eu.darken.butler.common.root.service.RootServiceConnection { public static final java.lang.String DESCRIPTOR; }
-keep class eu.darken.butler.common.root.service.RootServiceConnection$Stub
-keep class eu.darken.butler.common.root.service.RootServiceConnection$Stub$Proxy { <init>(android.os.IBinder); }
-keep class eu.darken.butler.common.adb.AdbServiceConnection { public static final java.lang.String DESCRIPTOR; }
-keep class eu.darken.butler.common.adb.AdbServiceConnection$Stub
-keep class eu.darken.butler.common.adb.AdbServiceConnection$Stub$Proxy { <init>(android.os.IBinder); }
