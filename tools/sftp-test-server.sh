#!/usr/bin/env bash
# Runs the OpenSSH SFTP server of lib-ssh's contract suite (same image, users, host keys and user
# keys as lib-ssh/src/test/kotlin/eu/darken/ssh/TestServers.kt) as a named Docker container on a
# fixed host port, bound to loopback, which an emulator reaches as 10.0.2.2.
#
# Users: butler/butlerpass (password) and keyuser (keys from lib-ssh/src/test/resources/keys).
# Each user sees a chrooted `/` with a writable `/upload` holding `hello.txt` ("Hello from Butler\n")
# and `docs/readme.md`. butler's `/upload` also holds `random-16m.bin` (16 MiB): the 8 bytes at offset
# 8*i are the big-endian 64-bit value i * 0x9E3779B97F4A7C15 (mod 2^64), so a reader can verify any
# range without a copy of the file.
#
# Usage: tools/sftp-test-server.sh start|stop|status
# Env: SFTP_TEST_PORT (default 2222), SFTP_TEST_CONTAINER (default butler-sftp-test)
set -euo pipefail

IMAGE=atmoz/sftp@sha256:75dcc29683ad479bdb99010666f2f55e99014350a1e862fe32b565845feb0fcd
NAME=${SFTP_TEST_CONTAINER:-butler-sftp-test}
PORT=${SFTP_TEST_PORT:-2222}
KEYS="$(cd "$(dirname "$0")/.." && pwd)/lib-ssh/src/test/resources/keys"
USERS=(butler:butlerpass:1001:100:upload keyuser::1002:100:upload)

stop() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}

running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]
}

status() {
    if running; then
        echo "$NAME running: $(docker port "$NAME" 22/tcp | head -n1)"
    else
        echo "$NAME not running"
        return 1
    fi
}

# The image runs /etc/sftp.d/* as root after creating the users and before starting sshd.
# docker cp keeps host ownership, so this script sets owners and modes itself.
setup_script() {
    cat <<'EOF'
#!/bin/bash
set -euo pipefail
src=/butler-test
for name in ed25519 rsa; do
    install -o root -g root -m 600 "$src/host_$name" "/etc/ssh/ssh_host_${name}_key"
    install -o root -g root -m 644 "$src/host_$name.pub" "/etc/ssh/ssh_host_${name}_key.pub"
done
install -d -o keyuser -g users -m 700 /home/keyuser/.ssh
cat "$src"/user_*.pub >/home/keyuser/.ssh/authorized_keys
chown keyuser:users /home/keyuser/.ssh/authorized_keys
chmod 600 /home/keyuser/.ssh/authorized_keys
for user in butler keyuser; do
    upload=/home/$user/upload
    mkdir -p "$upload/docs"
    printf 'Hello from Butler\n' >"$upload/hello.txt"
    printf '# Seed\n\nFixed content for SFTP tests.\n' >"$upload/docs/readme.md"
    chown -R "$user:users" "$upload"
done
install -o butler -g users -m 644 "$src/random-16m.bin" /home/butler/upload/random-16m.bin
EOF
}

start() {
    stop
    STAGING=$(mktemp -d)
    trap 'rm -rf "$STAGING"' EXIT
    mkdir -p "$STAGING/butler-test" "$STAGING/sftp.d"
    for name in host_ed25519 host_rsa; do
        cp "$KEYS/$name" "$KEYS/$name.pub" "$STAGING/butler-test/"
    done
    for name in user_ed25519 user_rsa_pem user_ecdsa_pkcs8; do
        cp "$KEYS/$name.pub" "$STAGING/butler-test/"
    done
    python3 - "$STAGING/butler-test/random-16m.bin" <<'PY'
import struct, sys
mask = (1 << 64) - 1
with open(sys.argv[1], "wb") as out:
    out.write(b"".join(struct.pack(">Q", (i * 0x9E3779B97F4A7C15) & mask) for i in range(2 * 1024 * 1024)))
PY
    setup_script >"$STAGING/sftp.d/butler-test.sh"
    chmod 755 "$STAGING/sftp.d/butler-test.sh"

    docker create --name "$NAME" -p "127.0.0.1:$PORT:22" "$IMAGE" "${USERS[@]}" >/dev/null
    docker cp "$STAGING/butler-test" "$NAME:/butler-test" >/dev/null
    docker cp "$STAGING/sftp.d" "$NAME:/etc/sftp.d" >/dev/null
    docker start "$NAME" >/dev/null

    local deadline=$((SECONDS + 60))
    until docker logs "$NAME" 2>&1 | grep -q 'Server listening on 0.0.0.0 port 22'; do
        if ! running || [ "$SECONDS" -ge "$deadline" ]; then
            docker logs "$NAME" >&2 || true
            echo "SFTP test server did not start" >&2
            stop
            return 1
        fi
        sleep 0.5
    done

    echo "SFTP test server $NAME listening on port $PORT"
    echo "Host key: $(docker exec "$NAME" ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)"
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    status) status ;;
    *)
        echo "Usage: $0 start|stop|status" >&2
        exit 2
        ;;
esac
