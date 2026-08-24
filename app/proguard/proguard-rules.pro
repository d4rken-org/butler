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

# Play Core KTX references this compile-time-only GMS annotation not on the runtime classpath
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# smbj authenticates with NTLM only (username/password or guest), so its Kerberos/SPNEGO path is
# unreachable - and Android has no JGSS implementation to reach anyway.
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# mbassador (smbj's event bus) offers optional EL-based message filtering, which needs a Java EE
# expression language implementation Android does not ship. smbj never declares such a filter.
# Listed per class rather than per package on purpose, same reasoning as the commons-compress block
# above: a future upgrade that makes these reachable should fail the build, not the app.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
