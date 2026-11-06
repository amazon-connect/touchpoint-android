#!/bin/sh
# Manifest-merge gate — a gate for a
# risk peculiar to Android. Every library manifest on a host's classpath merges into the host's own
# manifest, so anything declared there becomes the host's, silently: a <uses-permission> adds a
# permission the host's users are asked for and Play's listing shows; an <application> attribute
# overrides the host's backup, cleartext or theme settings; a component is exported in the host's
# name. Two checks, both against explicit allowlists so every entry is a review item:
#
#   own        the manifest inside each module's built release AAR — every source set merged —
#              declares nothing except the removals in REMOVALS (`tools:node="remove"`, which strips a
#              dependency's declaration at the host's merge);
#   inherited  the permissions, components and <application> attributes that the module's external
#              dependencies merge into the host, net of those removals, are exactly INHERITED. A new
#              one fails (a dependency bump must not change a host's permissions unseen), and so does
#              an allowlisted one that no longer appears (the allowlist stays true). AndroidX components
#              are the platform's own and are counted, not listed.
#
#   Scripts/check-manifests.sh                                       build, then both checks
#   Scripts/check-manifests.sh --aar <file> --module <m>             own check on one AAR (fixtures)
#   Scripts/check-manifests.sh --deps <manifest> --module <m>        inherited check, one dependency manifest (fixtures)
# Exit 0 clean, 1 on any finding, 2 on a failed build or a missing input.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
AAR=""; DEPS=""; MODULE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --aar) AAR=$2; shift 2 ;;
        --deps) DEPS=$2; shift 2 ;;
        --module) MODULE=$2; shift 2 ;;
        *) echo "unknown argument: $1"; exit 2 ;;
    esac
done

REPORT=$(mktemp); trap 'rm -f "$REPORT"' EXIT
if [ -z "$AAR$DEPS" ]; then
    TASKS=""
    for m in "$ROOT"/touchpoint-*/; do
        grep -q 'android.library' "$m/build.gradle.kts" 2>/dev/null || continue
        rm -rf "$m/build/outputs/aar"
        TASKS="$TASKS :$(basename "$m"):assembleRelease :$(basename "$m"):touchpointExternalAars"
    done
    # shellcheck disable=SC2086
    (cd "$ROOT" && ./gradlew -q --console=plain -Ptouchpoint.requireAndroid=true -I Scripts/lib/report-aars.init.gradle.kts $TASKS) > "$REPORT" 2>&1 \
        || { tail -20 "$REPORT"; echo "check-manifests: release build failed"; exit 2; }
fi

python3 -I - "$ROOT" "$AAR" "$DEPS" "$MODULE" "$REPORT" <<'PY'
import glob, os, sys, zipfile
import xml.etree.ElementTree as ET
root, one_aar, one_deps, module, report = sys.argv[1:6]
A = '{http://schemas.android.com/apk/res/android}'
T = '{http://schemas.android.com/tools}'

# module -> {permission: reason}. A removal strips a dependency's declaration at the host's merge:
# a library manifest outranks the manifests of its own dependencies.
REMOVALS = {
    'touchpoint-chat': {
        'android.permission.WAKE_LOCK': 'Media3 declares it for its wake mode; a video message never sets one, and a '
                                        'host must not be made to declare a permission it never uses',
    },
    'touchpoint-voice': {
        'android.permission.CAMERA': 'Chime declares it for video; Touchpoint voice is audio-only, and a '
                                     'host must not be made to declare a camera permission it never uses',
    },
}
# module -> {"<kind> <name>": reason}. What an opt-in module's own manifest declares, on purpose: the one
# exception to library manifests declaring nothing — a host that adds the module has chosen what it brings. Nothing
# else may declare anything.
DECLARED = {
    'touchpoint-voice-telecom': {
        'uses-permission android.permission.FOREGROUND_SERVICE': 'the background call: its foreground service',
        'uses-permission android.permission.FOREGROUND_SERVICE_PHONE_CALL': 'the background call: the service is of the phone-call type',
        'uses-permission android.permission.FOREGROUND_SERVICE_MICROPHONE': 'the background call: the microphone goes on while the app is away',
        'application/service com.amazon.connect.touchpoint.voice.telecom.TouchpointCallService': 'the background call: not exported',
    },
}
# A module's dependency on another of ours brings that one's removals: its manifest outranks theirs.
REMOVALS_FROM = {'touchpoint-voice-telecom': ['touchpoint-voice']}
# module -> {"<kind> <name>": reason}. What the module's external dependencies merge into the host.
INHERITED = {
    # Voice's, and core-telecom's: the point of opting in. Its ConnectionService and mute receiver are
    # AndroidX components, counted with the platform's.
    'touchpoint-voice-telecom': {
        'uses-permission ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION': 'androidx.core, through the UI kit: keeps ContextCompat receivers private to the host',
        'uses-permission android.permission.ACCESS_NETWORK_STATE': 'Chime, through voice: network-quality monitoring',
        'uses-permission android.permission.INTERNET': 'Chime, through voice: media and signalling',
        'uses-permission android.permission.MANAGE_OWN_CALLS': 'core-telecom: a self-managed call, which the phone-call service type needs',
        'uses-permission android.permission.MODIFY_AUDIO_SETTINGS': 'core-telecom: the call\'s audio route and mute',
        'uses-permission android.permission.BLUETOOTH_CONNECT': 'core-telecom: names the headset a call is on (a runtime permission it does not ask for)',
    },
    'touchpoint-core-android': {},
    # Compose for the declarative actions (plan item 14), and through it androidx.core.
    'touchpoint-livesync': {
        'uses-permission ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION': 'androidx.core, through Compose: keeps ContextCompat receivers private to the host',
        'uses-permission android.permission.INTERNET': 'OkHttp 5: the Live Sync socket and context POST',
        'application/provider androidx.startup.InitializationProvider': 'OkHttp 5 (okhttp-android): an androidx.startup initializer that hands OkHttp the app context, unexported',
    },
    # The UI kit: Compose, and through it androidx.core, as the chat module had before it was split out.
    'touchpoint-ui': {
        'uses-permission ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION': 'androidx.core: keeps ContextCompat receivers private to the host',
    },
    'touchpoint-chat': {
        'uses-permission android.permission.ACCESS_NETWORK_STATE': 'amazon-connect-chat-android: connectivity monitoring for reconnection',
        'uses-permission android.permission.INTERNET': 'OkHttp 5, through amazon-connect-chat-android 3.0: the participant service and its socket',
        'application/provider androidx.startup.InitializationProvider': 'OkHttp 5 (okhttp-android), through amazon-connect-chat-android 3.0: an androidx.startup initializer that hands OkHttp the app context, unexported',
        # androidx.core (through Compose): a signature permission scoped to the host's own
        # package, which ContextCompat uses to keep its dynamically registered receivers private. Already
        # in every app that links AndroidX; grants nothing to anyone else.
        'uses-permission ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION': 'androidx.core: keeps ContextCompat receivers private to the host',
    },
    'touchpoint-voice': {
        'uses-permission ${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION': 'androidx.core, through the UI kit: keeps ContextCompat receivers private to the host',
        'uses-permission android.permission.ACCESS_NETWORK_STATE': 'Chime: network-quality monitoring',
        'uses-permission android.permission.INTERNET': 'Chime: media and signalling',
    },
}
# Present in every AAR manifest and inert at the host's merge.
INERT = {'uses-sdk'}

ok = True
def fail(msg):
    global ok
    ok = False
    print("FAIL " + msg)

def parse(xml_bytes, label):
    try:
        return ET.fromstring(xml_bytes)
    except ET.ParseError as e:
        fail(f"{label}: manifest is not well-formed XML: {e}"); return None

def check_own(mod, manifest):
    allowed = REMOVALS.get(mod, {})
    declared = DECLARED.get(mod, {})
    found = set()
    for attr in manifest.attrib:
        if attr not in ('package', 'versionCode', 'versionName', A + 'versionCode', A + 'versionName'):
            fail(f"{mod}: <manifest> carries attribute {attr.replace(A, 'android:')}")
    for el in manifest:
        name = el.attrib.get(A + 'name', '')
        if el.tag in INERT:
            continue
        if el.attrib.get(T + 'node') == 'remove' and el.tag.startswith('uses-permission') and name in allowed:
            continue
        if f"{el.tag} {name}" in declared:
            found.add(f"{el.tag} {name}"); continue
        # An <application> carrying only reviewed components, and no attribute that would override the host's.
        if el.tag == 'application' and not el.attrib and declared:
            for child in el:
                key = f"application/{child.tag} {child.attrib.get(A + 'name', '')}"
                if key in declared:
                    found.add(key)
                    # Reviewed by name, and kept private: never exported, never reachable by an intent filter.
                    if child.attrib.get(A + 'exported') != 'false':
                        fail(f"{mod}: <{key}> must declare android:exported=\"false\"")
                    if any(g.tag == 'intent-filter' for g in child):
                        fail(f"{mod}: <{key}> carries an intent-filter, which would let other apps reach it")
                else:
                    fail(f"{mod}: own manifest declares <{key}>, which merges into the host's manifest")
            continue
        fail(f"{mod}: own manifest declares <{el.tag}{' ' + name if name else ''}>, which merges into the host's manifest")
    for key in sorted(set(declared) - found):
        fail(f"{mod}: DECLARED lists {key}, which the packaged manifest no longer declares — drop the entry")
    for perm in allowed:
        if not any(el.attrib.get(A + 'name') == perm and el.attrib.get(T + 'node') == 'remove' for el in manifest):
            fail(f"{mod}: REMOVALS lists {perm}, but the packaged manifest does not remove it")

def contributions(manifest):
    """`<kind> <name>` strings a dependency manifest merges into a host."""
    for el in manifest:
        name = el.attrib.get(A + 'name', '')
        if el.tag in INERT:
            continue
        if el.tag == 'application':
            for attr in el.attrib:
                yield f"application@{attr.replace(A, 'android:')}"
            for child in el:
                yield f"application/{child.tag} {child.attrib.get(A + 'name', '')}"
        else:
            yield f"{el.tag} {name}"

def check_inherited(mod, deps):
    """deps: [(coordinate, manifest element)]."""
    # A module's own removals; none of our modules depends on another that removes anything.
    removed = {f"uses-permission {p}" for m in [mod] + REMOVALS_FROM.get(mod, []) for p in REMOVALS.get(m, {})}
    allowed = INHERITED.get(mod)
    if allowed is None:
        fail(f"{mod}: no INHERITED entry — declare what it may inherit before it ships"); return
    seen, platform = {}, 0
    for coord, manifest in deps:
        for c in contributions(manifest):
            if c in removed:
                continue
            if coord.startswith('androidx.') and not c.startswith('uses-permission'):
                platform += 1                          # AndroidX's own components: the platform
                continue
            seen.setdefault(c, coord)
    for c, coord in sorted(seen.items()):
        if c not in allowed:
            fail(f"{mod}: inherits {c} from {coord} — not in INHERITED; decide it (allowlist with a reason, or remove it)")
    for c in sorted(set(allowed) - set(seen)):
        fail(f"{mod}: INHERITED lists {c}, which no dependency declares any more — drop the entry")
    if not [c for c in seen if c not in allowed]:
        print(f"ok   {mod}: inherits {len(seen)} reviewed declaration(s)" + (f", {platform} AndroidX component(s)" if platform else ""))

if one_aar or one_deps:
    mod = module or '(fixture)'
    if one_aar:
        if not os.path.isfile(one_aar):
            print(f"check-manifests: no such AAR: {one_aar}"); sys.exit(2)
        with zipfile.ZipFile(one_aar) as z:
            m = parse(z.read('AndroidManifest.xml'), one_aar)
        if m is not None:
            check_own(mod, m)
    if one_deps:
        if not os.path.isfile(one_deps):
            print(f"check-manifests: no such manifest: {one_deps}"); sys.exit(2)
        m = parse(open(one_deps, 'rb').read(), one_deps)
        if m is not None:
            check_inherited(mod, [('fixture:dependency', m)])
else:
    deps = {}
    for line in open(report, encoding='utf-8', errors='replace'):
        parts = line.rstrip('\n').split('\t')
        if len(parts) == 4 and parts[0] == 'TOUCHPOINT-AAR':
            deps.setdefault(parts[1], []).append((parts[2], parts[3]))
    modules = [os.path.basename(m) for m in sorted(glob.glob(os.path.join(root, 'touchpoint-*')))
               if os.path.isfile(os.path.join(m, 'build.gradle.kts')) and 'android.library' in open(os.path.join(m, 'build.gradle.kts')).read()]
    for mod in modules:
        aar = os.path.join(root, mod, 'build', 'outputs', 'aar', f'{mod}-release.aar')
        if not os.path.isfile(aar):
            fail(f"{mod}: no release AAR was built"); continue
        with zipfile.ZipFile(aar) as z:
            own = parse(z.read('AndroidManifest.xml'), mod)
        if own is not None:
            check_own(mod, own)
        parsed = []
        for coord, path in deps.get(mod, []):
            with zipfile.ZipFile(path) as z:
                if 'AndroidManifest.xml' in z.namelist():
                    m = parse(z.read('AndroidManifest.xml'), coord)
                    if m is not None:
                        parsed.append((coord, m))
        check_inherited(mod, parsed)
print("check-manifests: " + ("ok" if ok else "FAILED"))
sys.exit(0 if ok else 1)
PY
