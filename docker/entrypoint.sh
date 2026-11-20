#!/bin/sh
# Container entrypoint. Since 1.24.0 the image runs as an unprivileged user
# (approvals, uid 10001) instead of root, so a flaw in the application no
# longer hands an attacker root inside the container.
#
# The one thing that can go wrong with that is a volume created by an older,
# root-running release: its files belong to root and this user cannot write
# them. Left alone, that surfaces as a long H2 stack trace that says nothing
# useful. So the mounts are checked first and the fix is printed instead.
set -u

ME="$(id -u):$(id -g)"
bad=""
for dir in /app/data /app/control; do
    [ -d "$dir" ] || continue
    if ! [ -w "$dir" ] || ! [ -x "$dir" ]; then
        bad="$bad $dir"
        continue
    fi
    # The directory can be writable while the database inside it is not.
    for f in "$dir"/omnissa-approval.mv.db "$dir"/logs; do
        [ -e "$f" ] && ! [ -w "$f" ] && bad="$bad $f"
    done
done

if [ -n "$bad" ]; then
    cat >&2 <<MSG
================================================================================
Access Approval Tool: cannot write to:$bad

Since 1.24.0 the container runs as an unprivileged user ($ME), not root.
These paths were created by an earlier release and still belong to root.

Fix, once, on the Docker host. Either:

  * ZimaCube, or any host using the bundled updater: update the checkout that
    holds deploy/zimacube/update.sh, then deploy again. The updater and
    deploy.sh hand the directories to the image's user before each start.

  * Any other Compose install, from the directory with the compose file
    (replace "app" with the service name):

      docker compose run --rm --user 0:0 --cap-add CHOWN --cap-add DAC_OVERRIDE \\
        --cap-add FOWNER --entrypoint chown app -R 10001:10001 /app/data /app/control

To keep running as root instead, add   user: "0:0"   to the service.
Nothing has been changed. The container will now exit.
================================================================================
MSG
    exit 78
fi

# -XX:-UseBASE64Intrinsics turns off the hardware-accelerated Base64 code in
# the JVM. That code is where CVE-2026-114938 ("Java4Shell") lives: a memory
# corruption reachable before sign-in, on x86-64 hosts with AVX-512 VBMI. The
# flag is the mitigation the finders and Omnissa (KB 6001932) both document,
# it is accepted on arm64 too, and the cost is slightly slower Base64. It
# stays until the image is built on a Java runtime that carries the fix.
exec java -XX:-UseBASE64Intrinsics -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar \
    --spring.config.additional-location=file:./config/ "$@"
