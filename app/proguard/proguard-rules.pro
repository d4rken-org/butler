-keep class eu.darken.butler.BuildConfig { *; }
-dontobfuscate

-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# commons-compress references the zstd-jni and xz codecs, which we don't ship.
# ArchiveFormat only exposes ZIP/TAR/TAR_GZ/TAR_BZ2, so those code paths are unreachable.
# Listed per class rather than per package on purpose: if a commons-compress upgrade or new
# archive format makes these reachable, R8 should fail the build so we notice, instead of us
# shipping a NoClassDefFoundError. Add the codec dependency then, don't widen this list.
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream
