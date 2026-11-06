#!/bin/sh
# The realistic-competing-pin check — in Gradle.
#
# An integrator's app already carries its own versions of the libraries Touchpoint depends on. This
# is the one place in the repository where that dependency graph exists. Fixtures under
# Tests/Fixtures/competing-pins, each a host build depending on Touchpoint by composite:
#
#   below-chat/         amazon-connect-chat-android below our floor   → resolves at the floor or above
#   below-aws/          the AWS SDK for Kotlin's connectparticipant (the Connect SDK's own dependency) below *its* floor → same
#   below-chime/        Chime (touchpoint-voice's dependency) below our floor → same
#   below-coroutines/   kotlinx-coroutines below our floor → same
#   at-floor/           every watched module pinned strictly at the floor → resolves, exactly there
#   strict-below-chat/  amazon-connect-chat-android pinned *strictly* below our floor
#
# Gradle's version semantics are its own, and this check holds them in place. A plain
# version is a floor: the highest version asked for wins, so an older host pin is raised, not a
# conflict. A `strictly` pin (or `force`) is the consuming build's last word — Gradle lets it win over
# anything a library publishes, `reject` and strict ranges included (tried). For that
# case the check holds what an integrator can see: their dependency report names the module, our
# floor and their pin. If Gradle ever starts refusing it, this fails so the case is revisited.
#
# Network: Gradle fetches the fixtures' versions once into its cache. Needs the Android SDK.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIX="$ROOT/Tests/Fixtures/competing-pins"
# The fixtures are builds of their own and read no local.properties: hand them the SDK the root uses.
if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ROOT/local.properties" ]; then
    ANDROID_HOME=$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1/p' "$ROOT/local.properties" | head -1)
    if [ -n "$ANDROID_HOME" ]; then export ANDROID_HOME; fi
fi
SCRATCH=$(mktemp -d)
cleanup() { rm -rf "$SCRATCH"; for d in "$FIX"/*/; do rm -rf "$d.gradle" "$d.kotlin" "${d}build"; done; }
trap cleanup EXIT
fail() { echo "check-competing-pins: FAIL — $*" >&2; exit 1; }
gradle() { f=$1; shift; "$ROOT/gradlew" --console=plain -q --no-configuration-cache -p "$FIX/$f" "$@"; }
resolve() { gradle "$1" resolvePins > "$SCRATCH/$1.log" 2>&1; }
resolved() { sed -n "s/^RESOLVED $2:\(.*\)\$/\1/p" "$SCRATCH/$1.log" | head -1; }
at_least() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" = "$2" ]; } # $1 >= $2

# Floors as declared, read from the catalog so this check cannot drift from it.
floor() { sed -nE "s/^$1 = \"([^\"]+)\".*/\1/p" "$ROOT/gradle/libs.versions.toml"; }
CONNECT_FLOOR=$(floor connectChat); CHIME_FLOOR=$(floor chime); COROUTINES_FLOOR=$(floor kotlinxCoroutines)
[ -n "$CONNECT_FLOOR" ] && [ -n "$CHIME_FLOOR" ] && [ -n "$COROUTINES_FLOOR" ] || fail "could not read the floors from gradle/libs.versions.toml"

# 1. At the floor: resolves, exactly there. The AWS SDK floor is the Connect SDK's, not ours: read it
#    from what this graph settles on, so an upstream bump moves the assertion with it.
resolve at-floor || fail "at-floor: pins at the floor did not resolve:
$(tail -8 "$SCRATCH/at-floor.log")"
for pair in "software.aws.connect:amazon-connect-chat-android $CONNECT_FLOOR" "software.aws.chimesdk:amazon-chime-sdk $CHIME_FLOOR" \
    "org.jetbrains.kotlinx:kotlinx-coroutines-core $COROUTINES_FLOOR"; do
    set -- $pair
    got=$(resolved at-floor "$1")
    [ "$got" = "$2" ] || fail "at-floor: $1 resolved to '${got:-nothing}', not the floor $2"
done
AWS_FLOOR=$(resolved at-floor aws.sdk.kotlin:connectparticipant)
[ -n "$AWS_FLOOR" ] || fail "at-floor: aws.sdk.kotlin:connectparticipant is not in the graph"
echo "check-competing-pins: at-floor resolves at the floors (connect-chat $CONNECT_FLOOR, chime $CHIME_FLOOR, coroutines $COROUTINES_FLOOR; AWS SDK $AWS_FLOOR)"

# 2. An older plain pin: resolves, and is raised to the floor or above.
expect_raised() { # fixture module floor
    resolve "$1" || fail "$1: did not resolve:
$(tail -8 "$SCRATCH/$1.log")"
    got=$(resolved "$1" "$2")
    [ -n "$got" ] || fail "$1: $2 is not in the graph"
    at_least "$got" "$3" || fail "$1: $2 resolved to $got, below the floor $3 — the floor is not being carried"
    echo "check-competing-pins: $1 raised $2 to $got (floor $3)"
}
expect_raised below-chat software.aws.connect:amazon-connect-chat-android "$CONNECT_FLOOR"
expect_raised below-aws aws.sdk.kotlin:connectparticipant "$AWS_FLOOR"
expect_raised below-chime software.aws.chimesdk:amazon-chime-sdk "$CHIME_FLOOR"
expect_raised below-coroutines org.jetbrains.kotlinx:kotlinx-coroutines-core "$COROUTINES_FLOOR"

# 3. A strict pin below the floor: Gradle lets the host win. Hold that the integrator's own
#    report shows the downgrade with both versions, and notice if Gradle's behaviour changes.
PIN=2.0.0
if ! resolve strict-below-chat; then
    # Only a version conflict means Gradle's semantics moved; anything else (network, SDK) is not that.
    grep -q "UNRESOLVED software.aws.connect:amazon-connect-chat-android" "$SCRATCH/strict-below-chat.log" \
        || fail "strict-below-chat: the build failed for a reason other than resolution:
$(tail -8 "$SCRATCH/strict-below-chat.log")"
    fail "strict-below-chat: Gradle now refuses a strict pin below a library's floor — revisit this check:
$(grep UNRESOLVED "$SCRATCH/strict-below-chat.log" | head -4)"
fi
got=$(resolved strict-below-chat software.aws.connect:amazon-connect-chat-android)
[ "$got" = "$PIN" ] || fail "strict-below-chat: expected the host's strict pin $PIN to win, got '${got:-nothing}' — revisit this check"
gradle strict-below-chat dependencyInsight --configuration releaseRuntimeClasspath --dependency amazon-connect-chat-android > "$SCRATCH/insight.log" 2>&1 \
    || fail "strict-below-chat: dependencyInsight failed:
$(tail -5 "$SCRATCH/insight.log")"
grep -q "amazon-connect-chat-android:$CONNECT_FLOOR -> $PIN" "$SCRATCH/insight.log" \
    || fail "strict-below-chat: the host's dependency report does not show our floor $CONNECT_FLOOR overridden by $PIN — not diagnosable"
grep -q "strictly $PIN" "$SCRATCH/insight.log" || fail "strict-below-chat: the report does not show the host's strict pin $PIN"
echo "check-competing-pins: strict-below-chat — Gradle lets the host's strict $PIN win; its report shows $CONNECT_FLOOR -> $PIN"
echo "check-competing-pins: ok"
