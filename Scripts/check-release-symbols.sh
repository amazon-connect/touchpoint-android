#!/bin/sh
# DevelopmentBackend accepts literal keys, so it must never reach a release build. Here that
# is the separate touchpoint-development artifact, which a host links with
# `debugImplementation`. This gate checks the half the package controls: no published module's
# release output contains the class. (check-build-graph.sh checks that none depends on the artifact.)
#
#   Scripts/check-release-symbols.sh                build every module's release output, inspect it
#   Scripts/check-release-symbols.sh --jar <path>   inspect one jar or AAR (fixtures)
# Outputs are deleted before the build, so a stale artifact is never graded, and every module must
# produce one. Exit 0 clean, 1 on a finding, 2 on a failed build or a missing artifact.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ONE=""
[ "${1:-}" = "--jar" ] && ONE=$2

if [ -z "$ONE" ]; then
    TASKS=""
    for m in "$ROOT"/touchpoint-*/; do
        name=$(basename "$m")
        case "$name" in touchpoint-development|touchpoint-e2e) continue ;; esac
        [ -f "$m/build.gradle.kts" ] || continue
        rm -rf "$m/build/outputs/aar" "$m/build/libs"
        if grep -q 'android.library' "$m/build.gradle.kts"; then TASKS="$TASKS :$name:assembleRelease"; else TASKS="$TASKS :$name:jar"; fi
    done
    # shellcheck disable=SC2086
    (cd "$ROOT" && ./gradlew -q --console=plain -Ptouchpoint.requireAndroid=true $TASKS) >/dev/null 2>&1 \
        || { echo "check-release-symbols: release build failed"; exit 2; }
fi

python3 -I - "$ROOT" "$ONE" <<'PY'
import glob, io, os, sys, zipfile
root, one = sys.argv[1], sys.argv[2]
FORBIDDEN = 'DevelopmentBackend'

def classes(path):
    """Class entries of a jar, or of classes.jar and libs/*.jar inside an AAR."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if path.endswith('.aar'):
            for inner in [n for n in names if n == 'classes.jar' or (n.startswith('libs/') and n.endswith('.jar'))]:
                with zipfile.ZipFile(io.BytesIO(z.read(inner))) as j:
                    yield from (n for n in j.namelist() if n.endswith('.class'))
        else:
            yield from (n for n in names if n.endswith('.class'))

if one:
    if not os.path.isfile(one):
        print(f"check-release-symbols: no such artifact: {one}"); sys.exit(2)
    artifacts = [one]
else:
    artifacts = []
    for m in sorted(glob.glob(os.path.join(root, 'touchpoint-*'))):
        name = os.path.basename(m)
        if name in ('touchpoint-development', 'touchpoint-e2e') or not os.path.isfile(os.path.join(m, 'build.gradle.kts')):
            continue
        found = glob.glob(os.path.join(m, 'build', 'outputs', 'aar', f'{name}-release.aar')) or \
            [j for j in glob.glob(os.path.join(m, 'build', 'libs', f'{name}-*.jar')) if not j.endswith(('-sources.jar', '-javadoc.jar'))]
        if not found:
            print(f"check-release-symbols: {name} produced no release artifact"); sys.exit(2)
        artifacts += found

hits = [(a, [c for c in classes(a) if FORBIDDEN in c]) for a in artifacts]
hits = [(a, f) for a, f in hits if f]
for a, found in hits:
    print(f"FAIL: {len(found)} class(es) matching '{FORBIDDEN}' in {os.path.relpath(a, root)}: {found[:3]}")
if hits:
    sys.exit(1)
print(f"check-release-symbols: ok (no '{FORBIDDEN}' in {len(artifacts)} release artifact(s))")
PY
