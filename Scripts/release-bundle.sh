#!/bin/sh
# The release bundle for the Maven Central Portal: the published libraries at `version`, in the Maven
# repository layout, each file with its .md5 and .sha1, zipped. Unsigned, and built with no
# credential: Scripts/sign-and-upload.sh signs and uploads it where the team's key is kept.
#
#   Scripts/release-bundle.sh [--group <group>] <bundle.zip>
#
# --group replaces gradle.properties' group, for a dry run on Sonatype's test namespace.
# Refuses a tree with changes, so the bundle is exactly a commit; prints that commit and the bundle's
# SHA-256, which sign-and-upload.sh is given to check the copy it signs.
set -eu

group=""
case "${1:-}" in
    --group) group=${2:?usage: Scripts/release-bundle.sh [--group <group>] <bundle.zip>}; shift 2 ;;
esac
out=${1:?usage: Scripts/release-bundle.sh [--group <group>] <bundle.zip>}
# Where the caller ran it from, not the repository root the script moves to.
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac
case "$out" in *.zip) ;; *) echo "release-bundle: $out: the bundle is a .zip" >&2; exit 2 ;; esac

root=$(git rev-parse --show-toplevel)
cd "$root"
fail() { echo "release-bundle: $*" >&2; exit 1; }

if [ -n "$(git status --porcelain --untracked-files=normal -- . ':!.agents')" ]; then
    fail "the tree has changes; a bundle is built from a commit only"
fi
commit=$(git rev-parse HEAD)

version=$(sed -n 's/^version=//p' gradle.properties)
[ -n "$group" ] || group=$(sed -n 's/^group=//p' gradle.properties)
[ -n "$version" ] && [ -n "$group" ] || fail "gradle.properties has no group or version"
case "$version" in *-SNAPSHOT) fail "$version is a snapshot; Maven Central takes releases only" ;; esac
grep -q "^## \[$version\]" CHANGELOG.md || fail "CHANGELOG.md has no section for $version"

# The published modules, as the root build names them: one source of truth for what may leave.
modules=$(sed -n 's/^ *"\(touchpoint-[a-z-]*\)" to ".*/\1/p' build.gradle.kts)
[ -n "$modules" ] || fail "no published modules found in build.gradle.kts"

staging=build/staging-deploy
rm -rf "$staging"
./gradlew --console=plain -q -Ptouchpoint.requireAndroid=true -Pgroup="$group" \
    publishAllPublicationsToLocalStagingRepository

# Only what Central takes: no repository metadata, no stray module.
find "$staging" -name 'maven-metadata.xml*' -exec rm -f {} +
gpath=$(printf '%s' "$group" | tr . /)
[ -d "$staging/$gpath" ] || fail "nothing was staged under $group"
for found in "$staging/$gpath"/*; do
    name=${found##*/}
    printf '%s\n' "$modules" | grep -qx "$name" || fail "$name was staged but is not a published module"
done

for m in $modules; do
    dir="$staging/$gpath/$m/$version"
    base="$dir/$m-$version"
    [ -d "$dir" ] || fail "$m: not staged at $version"
    for f in "$base.pom" "$base.module" "$base-sources.jar" "$base-javadoc.jar"; do
        [ -f "$f" ] || fail "$m: missing ${f##*/}"
    done
    [ -f "$base.aar" ] || [ -f "$base.jar" ] || fail "$m: no .aar or .jar"
    grep -q "<groupId>$group</groupId>" "$base.pom" || fail "$m: the POM's groupId is not $group"
    for tag in name description url license developer scm; do
        grep -q "<$tag>" "$base.pom" || fail "$m: the POM has no <$tag>"
    done
done

# Every file Central receives carries the two checksums it requires.
for f in $(find "$staging" -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512'); do
    [ -f "$f.md5" ] && [ -f "$f.sha1" ] || fail "${f#"$staging"/}: missing its .md5 or .sha1"
done

mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/${out##*/}"
rm -f "$out"
(cd "$staging" && zip -q -r -X "$out" .)

sha=$(shasum -a 256 "$out" | cut -d' ' -f1)
count=$(unzip -Z1 "$out" | grep -vc '/$')
echo "release-bundle: $group, version $version, commit $commit"
echo "release-bundle: $out ($count files, unsigned)"
echo "release-bundle: sha256 $sha"
