package eu.darken.webdav

import java.time.Duration
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable

/**
 * Nextcloud 35.0.1 (official Apache image) on SQLite, auto-installed with [USER] as admin. Brute-force
 * throttling is off so wrong-password checks do not slow later requests, and [USER] gets a 1 GB quota
 * so `quota-available-bytes` is not Nextcloud's unlimited sentinel.
 */
internal class NextcloudServer :
    GenericContainer<NextcloudServer>(
        "nextcloud@sha256:8f2f0e93aca5e9457019933936dea1f313052e72c696b4aecb31580a208b859f"
    ) {
    init {
        withExposedPorts(80)
        withEnv("SQLITE_DATABASE", "nextcloud")
        withEnv("NEXTCLOUD_ADMIN_USER", USER)
        withEnv("NEXTCLOUD_ADMIN_PASSWORD", PASSWORD)
        withEnv("NEXTCLOUD_TRUSTED_DOMAINS", "localhost 127.0.0.1")
        waitingFor(
            Wait.forHttp("/status.php")
                .forResponsePredicate { it.contains("\"installed\":true") }
                .withStartupTimeout(Duration.ofMinutes(5))
        )
    }

    private fun occ(vararg args: String) {
        val result = execInContainer("runuser", "-u", "www-data", "--", "php", "/var/www/html/occ", *args)
        check(result.exitCode == 0) { "occ ${args.joinToString(" ")} failed: ${result.stdout} ${result.stderr}" }
    }

    fun endpoint(): WebDavEndpoint = WebDavEndpoint("http://$host:${getMappedPort(80)}/remote.php/dav/files/$USER/")

    companion object {
        const val USER = "butler"
        const val PASSWORD = "Butler-Contract-Test-2026"

        val shared: NextcloudServer by lazy {
            NextcloudServer().also {
                it.start()
                it.occ("config:system:set", "auth.bruteforce.protection.enabled", "--value=false", "--type=boolean")
                it.occ("user:setting", USER, "files", "quota", "1 GB")
            }
        }
    }
}

/**
 * Stock Apache httpd 2.4.69 with mod_dav_fs: `/dav/` requires Basic auth for [USER], `/public/` is
 * anonymous. mod_dir's default `DirectorySlash On` stays, so collection URLs without a trailing slash
 * are redirected. mod_dav_fs in this release reports no quota.
 */
internal class ApacheDavServer :
    GenericContainer<ApacheDavServer>(
        "httpd@sha256:c842ac797bd1fe8b3cd26d002ac9d948aa2814097544600600b35aef42e6785a"
    ) {
    init {
        withExposedPorts(80)
        withCopyToContainer(Transferable.of(CONFIG.encodeToByteArray(), 0x1a4), "/usr/local/apache2/conf/httpd.conf")
        withCommand(
            "sh",
            "-c",
            "mkdir -p /srv/dav /srv/public /usr/local/apache2/var && " +
                "chown www-data:www-data /srv/dav /srv/public /usr/local/apache2/var && " +
                "htpasswd -bc /usr/local/apache2/conf/dav.htpasswd $USER $PASSWORD && " +
                "exec httpd-foreground",
        )
        waitingFor(Wait.forLogMessage(".*resuming normal operations.*", 1))
    }

    fun endpoint(path: String = "dav"): WebDavEndpoint = WebDavEndpoint("http://$host:${getMappedPort(80)}/$path/")

    companion object {
        const val USER = "butler"
        const val PASSWORD = "butlerpass"

        val shared: ApacheDavServer by lazy { ApacheDavServer().also { it.start() } }

        private val CONFIG =
            """
            ServerRoot "/usr/local/apache2"
            Listen 80
            LoadModule mpm_event_module modules/mod_mpm_event.so
            LoadModule unixd_module modules/mod_unixd.so
            LoadModule log_config_module modules/mod_log_config.so
            LoadModule mime_module modules/mod_mime.so
            LoadModule alias_module modules/mod_alias.so
            LoadModule dir_module modules/mod_dir.so
            LoadModule authn_core_module modules/mod_authn_core.so
            LoadModule authn_file_module modules/mod_authn_file.so
            LoadModule authz_core_module modules/mod_authz_core.so
            LoadModule authz_user_module modules/mod_authz_user.so
            LoadModule auth_basic_module modules/mod_auth_basic.so
            LoadModule dav_module modules/mod_dav.so
            LoadModule dav_fs_module modules/mod_dav_fs.so
            User www-data
            Group www-data
            ServerName localhost
            ErrorLog /proc/self/fd/2
            LogLevel notice
            LogFormat "%r %>s" request
            CustomLog /proc/self/fd/1 request
            TypesConfig conf/mime.types
            DocumentRoot "/usr/local/apache2/htdocs"
            DavLockDB "/usr/local/apache2/var/DavLock"
            Alias /dav /srv/dav
            Alias /public /srv/public
            <Directory "/srv/dav">
                Dav On
                AuthType Basic
                AuthName "Butler WebDAV"
                AuthUserFile "/usr/local/apache2/conf/dav.htpasswd"
                Require valid-user
            </Directory>
            <Directory "/srv/public">
                Dav On
                Require all granted
            </Directory>
            """.trimIndent() + "\n"
    }
}
