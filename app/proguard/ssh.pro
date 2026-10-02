# SFTP (:lib-ssh): Apache MINA SSHD and the private Bouncy Castle provider it is handed.

# Referenced by MINA but absent from Android's runtime, in debug builds as much as in minified ones.
# ExceptionUtils.peelException tests for the javax.management types; FilePasswordProvider.decode and
# AbstractPEMResourceKeyPairParser throw the javax.security.auth.login ones for a key without a
# passphrase.
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException

# slf4j 1.7's LoggerFactory binds to this class if present and otherwise falls back to its no-op logger.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# MINA instantiates its NIO2 transport from a class literal via getDeclaredConstructor().
-keepclassmembers class org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory {
    public <init>();
}

# EventListenerUtils.proxyWrapper hands out java.lang.reflect.Proxy instances of listener interfaces.
# Without implementations R8 sees, it rewrites the cast of such a proxy into a ClassCastException.
-keep,allowobfuscation interface * extends org.apache.sshd.common.util.SshdEventListener

# BouncyCastleSecurityProviderRegistrar looks both up by name and reads PROVIDER_NAME reflectively.
# Without them MINA treats Bouncy Castle, and with it Ed25519, as unavailable.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider {
    public static final java.lang.String PROVIDER_NAME;
}
-keep interface org.bouncycastle.jcajce.interfaces.EdDSAKey

# BouncyCastleProvider loads each algorithm family's <family>$Mappings by name, and those register
# their implementations by class name. Kept: the families MINA requests from the provider (every
# entity but Cipher and Mac, see MinaSetup) for its default key exchanges and signatures, MD5 for
# encrypted PEM keys, and PBKDF2 plus AES for encrypted PKCS#8 keys.
-keep class org.bouncycastle.jcajce.provider.asymmetric.DH$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dh.** { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { <init>(); }
# Digest and symmetric families name their implementations after the outer class, so it keeps its name.
-keep class org.bouncycastle.jcajce.provider.digest.MD5
-keep class org.bouncycastle.jcajce.provider.digest.MD5$* { <init>(); }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$* { <init>(); }
-keep class org.bouncycastle.jcajce.provider.digest.SHA384
-keep class org.bouncycastle.jcajce.provider.digest.SHA384$* { <init>(); }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512
-keep class org.bouncycastle.jcajce.provider.digest.SHA512$* { <init>(); }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES
-keep class org.bouncycastle.jcajce.provider.symmetric.AES$* { <init>(); }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF2
-keep class org.bouncycastle.jcajce.provider.symmetric.PBEPBKDF2$* { <init>(); }
