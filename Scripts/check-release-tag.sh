#!/bin/sh
# Whether a release tag fits the tree, and the release's notes.
#
#   Scripts/check-release-tag.sh <tag>           the tag is v<version>, where <version> is
#                                                gradle.properties' and is not a snapshot, and
#                                                CHANGELOG.md has a dated section for it
#   Scripts/check-release-tag.sh --notes <tag>   prints that section's body, for the GitHub release
#
# The release workflow runs it on the tagged commit; CI runs it on a pull request that changes the
# version, so a release pull request is checked before its tag can be pushed, when it can still change.
set -eu
notes=no
if [ "${1:-}" = --notes ]; then notes=yes; shift; fi
tag=${1:?usage: Scripts/check-release-tag.sh [--notes] <tag>}
root=$(cd "$(dirname "$0")/.." && pwd)
fail() { echo "check-release-tag: $*" >&2; exit 1; }

version=$(sed -n 's/^version=\(.*\)$/\1/p' "$root/gradle.properties" | head -1)
[ -n "$version" ] || fail "gradle.properties names no version"
case "$version" in *-SNAPSHOT) fail "$version is a snapshot: set the release's version first" ;; esac
printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' \
    || fail "$version is not a semantic version"
[ "$tag" = "v$version" ] || fail "the tag is $tag, but gradle.properties' version is $version (tag v$version)"

# The SDK reports its own version (Touchpoint.VERSION): it must be the one being released.
reported=$(sed -n 's/.*public val VERSION: String = "\([^"]*\)".*/\1/p' "$root/touchpoint-core/src/main/kotlin/com/amazon/connect/touchpoint/core/Touchpoint.kt" | head -1)
[ "$reported" = "$version" ] || fail "Touchpoint.VERSION is ${reported:-missing}, but gradle.properties' version is $version"

# The section is `## [<version>] - <yyyy-mm-dd>`, up to the next `## ` heading.
heading=$(grep -E "^## \[$(printf '%s' "$version" | sed 's/\./\\./g')\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$" "$root/CHANGELOG.md" || true)
[ -n "$heading" ] || fail "CHANGELOG.md has no dated section for $version (## [$version] - <yyyy-mm-dd>)"

if [ "$notes" = yes ]; then
    awk -v h="$heading" '
        $0 == h { on = 1; next }
        on && /^## / { exit }
        on { print }
    ' "$root/CHANGELOG.md" | sed -e '/./,$!d'
else
    echo "check-release-tag: $tag fits: version $version, $heading"
fi
