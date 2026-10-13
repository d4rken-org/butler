#!/usr/bin/env bash
# Runs the Apache httpd mod_dav server of lib-webdav's contract suite (same image as
# lib-webdav/src/test/kotlin/eu/darken/webdav/TestServers.kt) as a named Docker container on fixed
# host ports, bound to loopback, which an emulator reaches as 10.0.2.2.
#
# HTTP and HTTPS serve the same WebDAV root at `/`, Basic auth butler/butlerpass, seeded on every
# start with `hello.txt` ("Hello from Butler\n") and `docs/readme.md`.
#
# HTTPS uses one of two self-signed certificates, A and B, generated once into the cache directory and
# reused, so their fingerprints stay stable across runs. `start` serves A, `rotate-cert` switches the
# running server to B without touching its files. `status` prints both SHA-256 fingerprints in the
# form the app shows (uppercase, colon-separated) and which one is served.
#
# Usage: tools/webdav-test-server.sh start|stop|status|rotate-cert
# Env: WEBDAV_TEST_HTTP_PORT (default 8080), WEBDAV_TEST_HTTPS_PORT (default 8443),
#      WEBDAV_TEST_CONTAINER (default butler-webdav-test),
#      WEBDAV_TEST_CERT_DIR (default ${XDG_CACHE_HOME:-$HOME/.cache}/butler-webdav-test-server)
set -euo pipefail

IMAGE=httpd@sha256:c842ac797bd1fe8b3cd26d002ac9d948aa2814097544600600b35aef42e6785a
NAME=${WEBDAV_TEST_CONTAINER:-butler-webdav-test}
HTTP_PORT=${WEBDAV_TEST_HTTP_PORT:-8080}
HTTPS_PORT=${WEBDAV_TEST_HTTPS_PORT:-8443}
CERTS=${WEBDAV_TEST_CERT_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/butler-webdav-test-server}
USER_NAME=butler
PASSWORD=butlerpass
CONF=/usr/local/apache2/conf

stop() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}

running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]
}

ensure_certs() {
    mkdir -p "$CERTS"
    local name tmp
    for name in a b; do
        [ -s "$CERTS/$name.crt" ] && [ -s "$CERTS/$name.key" ] && continue
        tmp=$(mktemp -d "$CERTS/.gen.XXXXXX")
        (
            umask 077
            openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=butler-webdav-test-$name" \
                -keyout "$tmp/key" -out "$tmp/crt" 2>/dev/null
        )
        mv "$tmp/key" "$CERTS/$name.key"
        mv "$tmp/crt" "$CERTS/$name.crt"
        rmdir "$tmp"
    done
}

fingerprint() {
    local out
    out=$(openssl x509 -noout -fingerprint -sha256 "$@")
    echo "${out#*=}"
}

served_fingerprint() {
    openssl s_client -connect "127.0.0.1:$HTTPS_PORT" </dev/null 2>/dev/null | fingerprint 2>/dev/null || true
}

status() {
    ensure_certs
    local a b served
    a=$(fingerprint -in "$CERTS/a.crt")
    b=$(fingerprint -in "$CERTS/b.crt")
    if running; then
        echo "$NAME running"
        echo "HTTP:  $(docker port "$NAME" 80/tcp | head -n1)"
        echo "HTTPS: $(docker port "$NAME" 443/tcp | head -n1)"
        served=$(served_fingerprint)
        case "$served" in
            "$a") echo "Serving certificate A" ;;
            "$b") echo "Serving certificate B" ;;
            *) echo "Serving an unknown certificate: ${served:-none}" ;;
        esac
    else
        echo "$NAME not running"
    fi
    echo "Certificate A: $a"
    echo "Certificate B: $b"
    running
}

httpd_conf() {
    cat <<EOF
ServerRoot "/usr/local/apache2"
Listen 80
Listen 443
LoadModule mpm_event_module modules/mod_mpm_event.so
LoadModule unixd_module modules/mod_unixd.so
LoadModule log_config_module modules/mod_log_config.so
LoadModule mime_module modules/mod_mime.so
LoadModule dir_module modules/mod_dir.so
LoadModule authn_core_module modules/mod_authn_core.so
LoadModule authn_file_module modules/mod_authn_file.so
LoadModule authz_core_module modules/mod_authz_core.so
LoadModule authz_user_module modules/mod_authz_user.so
LoadModule auth_basic_module modules/mod_auth_basic.so
LoadModule dav_module modules/mod_dav.so
LoadModule dav_fs_module modules/mod_dav_fs.so
LoadModule socache_shmcb_module modules/mod_socache_shmcb.so
LoadModule ssl_module modules/mod_ssl.so
User www-data
Group www-data
ServerName localhost
ErrorLog /proc/self/fd/2
LogLevel notice
LogFormat "%r %>s" request
CustomLog /proc/self/fd/1 request
TypesConfig conf/mime.types
DavLockDB "/usr/local/apache2/var/DavLock"
SSLSessionCache "shmcb:/usr/local/apache2/logs/ssl_scache(512000)"
DocumentRoot "/srv/dav"
<Directory "/srv/dav">
    Dav On
    AuthType Basic
    AuthName "Butler WebDAV"
    AuthUserFile "$CONF/dav.htpasswd"
    Require valid-user
</Directory>
<VirtualHost *:443>
    SSLEngine on
    SSLCertificateFile "$CONF/server.crt"
    SSLCertificateKeyFile "$CONF/server.key"
</VirtualHost>
EOF
}

start() {
    stop
    ensure_certs
    STAGING=$(mktemp -d)
    trap 'rm -rf "$STAGING"' EXIT
    mkdir -p "$STAGING/conf" "$STAGING/dav/docs"
    httpd_conf >"$STAGING/conf/httpd.conf"
    cp "$CERTS/a.crt" "$STAGING/conf/server.crt"
    cp "$CERTS/a.key" "$STAGING/conf/server.key"
    printf 'Hello from Butler\n' >"$STAGING/dav/hello.txt"
    printf '# Seed\n\nFixed content for WebDAV tests.\n' >"$STAGING/dav/docs/readme.md"

    docker create --name "$NAME" -p "127.0.0.1:$HTTP_PORT:80" -p "127.0.0.1:$HTTPS_PORT:443" "$IMAGE" \
        sh -c "mkdir -p /usr/local/apache2/var && \
            chown -R www-data:www-data /srv/dav /usr/local/apache2/var && \
            htpasswd -bc $CONF/dav.htpasswd $USER_NAME $PASSWORD && \
            exec httpd-foreground" >/dev/null
    docker cp "$STAGING/conf/." "$NAME:$CONF/" >/dev/null
    docker cp "$STAGING/dav" "$NAME:/srv/dav" >/dev/null
    if ! docker start "$NAME" >/dev/null; then
        echo "WebDAV test server did not start; are ports $HTTP_PORT and $HTTPS_PORT free?" >&2
        stop
        return 1
    fi

    local deadline=$((SECONDS + 60))
    until docker logs "$NAME" 2>&1 | grep -q 'resuming normal operations'; do
        if ! running || [ "$SECONDS" -ge "$deadline" ]; then
            docker logs "$NAME" >&2 || true
            echo "WebDAV test server did not start" >&2
            stop
            return 1
        fi
        sleep 0.5
    done

    echo "WebDAV test server $NAME listening on HTTP $HTTP_PORT and HTTPS $HTTPS_PORT"
    status
}

rotate_cert() {
    if ! running; then
        echo "$NAME not running" >&2
        return 1
    fi
    ensure_certs
    local b
    b=$(fingerprint -in "$CERTS/b.crt")
    docker cp "$CERTS/b.crt" "$NAME:$CONF/server.crt" >/dev/null
    docker cp "$CERTS/b.key" "$NAME:$CONF/server.key" >/dev/null
    docker exec "$NAME" httpd -k graceful

    local deadline=$((SECONDS + 30))
    until [ "$(served_fingerprint)" = "$b" ]; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            docker logs "$NAME" >&2 || true
            echo "WebDAV test server did not switch to certificate B" >&2
            return 1
        fi
        sleep 0.5
    done

    echo "WebDAV test server $NAME now serves certificate B"
    status
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    status) status ;;
    rotate-cert) rotate_cert ;;
    *)
        echo "Usage: $0 start|stop|status|rotate-cert" >&2
        exit 2
        ;;
esac
