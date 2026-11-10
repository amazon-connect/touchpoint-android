#!/bin/sh
# Signs a bundle from Scripts/release-bundle.sh with the team's GPG key and uploads it to the Maven
# Central Portal, to wait there for a person to review it and press Publish (or Drop). Run where the
# team's secrets are, never on a laptop: AWS CloudShell in the team's account, or the release
# pipeline later. Every secret is read from Secrets Manager into a private temporary directory, and
# that directory is deleted on exit; none is printed, and none is passed on a command line.
#
#   Scripts/sign-and-upload.sh --sha256 <the bundle's sha256, as release-bundle.sh printed it> <bundle.zip>
#
# The secrets, by id (override with the variable):
#   TP_SECRET_PORTAL_USERNAME  touchpoint-android/release/portal-username   Central Portal user token: name
#   TP_SECRET_PORTAL_PASSWORD  touchpoint-android/release/portal-password   Central Portal user token: password
#   TP_SECRET_GPG_KEY          touchpoint-android/release/gpg-private-key   the signing key, ASCII-armoured
#   TP_SECRET_GPG_PASSPHRASE   touchpoint-android/release/gpg-passphrase    its passphrase
#
# Needs aws, gpg with gpg-agent, curl, zip, unzip, sha256sum and python3. CloudShell has them all but
# the agent: run `sudo dnf swap -y gnupg2-minimal gnupg2-full` once in each session.
set -eu
usage="usage: Scripts/sign-and-upload.sh --sha256 <sha256> <bundle.zip>"
[ "${1:-}" = --sha256 ] || { echo "$usage" >&2; exit 2; }
want=${2:?$usage}
bundle=${3:?$usage}
portal=https://central.sonatype.com/api/v1/publisher

fail() { echo "sign-and-upload: $*" >&2; exit 1; }
for tool in aws gpg gpgconf curl zip unzip sha256sum python3; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is not installed"
done
# CloudShell's gnupg2-minimal has no agent, and gpg cannot use a secret key without one. Its system
# packages reset each session, so this is needed every time there.
command -v gpg-agent >/dev/null 2>&1 \
    || fail "gpg-agent is not installed (CloudShell: sudo dnf swap -y gnupg2-minimal gnupg2-full)"
[ -f "$bundle" ] || fail "$bundle: no such file"
got=$(sha256sum "$bundle" | cut -d' ' -f1)
[ "$got" = "$want" ] || fail "$bundle's sha256 is $got, not $want: not the bundle that was built"

umask 077
work=$(mktemp -d)
GNUPGHOME="$work/gnupg"
export GNUPGHOME
cleanup() {
    gpgconf --kill all >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 1' INT TERM HUP
mkdir "$GNUPGHOME" "$work/bundle"

unzip -q "$bundle" -d "$work/bundle"
files=$(cd "$work/bundle" && find . -type f | sed 's|^\./||' | sort)
[ -n "$files" ] || fail "$bundle is empty"
printf '%s\n' "$files" | grep -q -e '\.asc$' -e 'maven-metadata' && fail "$bundle is not a release-bundle.sh bundle (it has signatures or repository metadata)"
poms=$(printf '%s\n' "$files" | grep '\.pom$') || fail "$bundle has no POM"

secret() {
    aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text
}

# The key, into a key ring that lives only as long as this run.
secret "${TP_SECRET_GPG_KEY:-touchpoint-android/release/gpg-private-key}" | gpg --batch --quiet --import
secret "${TP_SECRET_GPG_PASSPHRASE:-touchpoint-android/release/gpg-passphrase}" >"$work/passphrase"
keys=$(gpg --batch --with-colons --list-secret-keys | grep -c '^sec' || true)
[ "$keys" = 1 ] || fail "the key secret holds $keys secret keys; it must hold exactly one"
fingerprint=$(gpg --batch --with-colons --list-secret-keys | awk -F: '/^fpr/ { print $10; exit }')

# A signature for every file but the checksums, each verified before anything leaves.
for f in $files; do
    case "$f" in *.md5|*.sha1|*.sha256|*.sha512) continue ;; esac
    gpg --batch --quiet --yes --pinentry-mode loopback --passphrase-file "$work/passphrase" \
        --local-user "$fingerprint" --armor --detach-sign --output "$work/bundle/$f.asc" "$work/bundle/$f"
    gpg --batch --quiet --verify "$work/bundle/$f.asc" "$work/bundle/$f" 2>/dev/null || fail "$f: its signature does not verify"
done
signed="$work/signed.zip"
(cd "$work/bundle" && zip -q -r -X "$signed" .)

# One release, one version: every module's, or nothing goes.
versions=$(for p in $poms; do basename "$(dirname "$p")"; done | sort -u)
[ "$(printf '%s\n' "$versions" | wc -l | tr -d ' ')" -eq 1 ] || fail "the bundle holds more than one version: $(echo $versions)"
version=$versions

echo "sign-and-upload: signed with $fingerprint. Coordinates:"
for p in $poms; do
    artifact=$(basename "$(dirname "$(dirname "$p")")")
    group=$(dirname "$(dirname "$(dirname "$p")")" | tr / .)
    echo "  $group:$artifact:$version"
done
printf 'sign-and-upload: upload these to the Central Portal, to wait for review? Type the version (%s) to go on: ' "$version"
# No answer (stdin closed, as in a pipeline) is no confirmation, said as such.
read -r answer || answer=
[ "$answer" = "$version" ] || fail "not confirmed; nothing was uploaded"

# The portal token, as the bearer credential the Publisher API takes, in a curl config file.
# Trimmed: a token copied from the portal's page tends to carry a space, and the portal compares exactly.
trim() { t=$1; t=${t#"${t%%[![:space:]]*}"}; t=${t%"${t##*[![:space:]]}"}; printf '%s' "$t"; }
user=$(trim "$(secret "${TP_SECRET_PORTAL_USERNAME:-touchpoint-android/release/portal-username}")")
pass=$(trim "$(secret "${TP_SECRET_PORTAL_PASSWORD:-touchpoint-android/release/portal-password}")")
printf 'header = "Authorization: Bearer %s"\n' "$(printf '%s:%s' "$user" "$pass" | base64 | tr -d '\n')" >"$work/curl.cfg"
unset user pass t

name="touchpoint-android-$version"
id=$(curl -sS --fail-with-body --config "$work/curl.cfg" -F "bundle=@$signed" \
    "$portal/upload?publishingType=USER_MANAGED&name=$name") || fail "the upload was refused: $id"
echo "sign-and-upload: uploaded as deployment $id; validating"

# USER_MANAGED: the portal validates, then holds it. Publishing stays a person's decision.
tries=0
while :; do
    status=$(curl -sS --fail-with-body --config "$work/curl.cfg" -X POST "$portal/status?id=$id") \
        || fail "the status check failed: $status"
    state=$(printf '%s' "$status" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["deploymentState"])')
    case "$state" in
        VALIDATED)
            echo "sign-and-upload: $id is VALIDATED and waiting. Review it at https://central.sonatype.com/publishing/deployments, then Publish or Drop."
            exit 0 ;;
        FAILED)
            printf '%s' "$status" | python3 -I -c 'import json, sys; print(json.dumps(json.load(sys.stdin).get("errors"), indent=2))' >&2
            fail "$id failed validation; drop it in the portal, fix, and build a new bundle" ;;
        PENDING|VALIDATING) ;;
        *) fail "$id is $state, which this script does not expect; see the portal" ;;
    esac
    tries=$((tries + 1))
    [ "$tries" -lt 90 ] || fail "$id is still $state after 15 minutes; see the portal"
    sleep 10
done
