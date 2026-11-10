#!/bin/sh
# Dependency-graph check: a chat-only consumer must not link the WebRTC media stack, and a
# Live-Sync-only consumer links neither external SDK. Also: no published module may carry the
# debug-only touchpoint-development artifact on its release classpath.
#
# Not a unit test — this is a property of the resolved dependency graph. Gradle's release runtime
# classpath of each module *is* its transitive closure, so a path such as chat → connect-chat-android →
# X → Chime is caught, not just a direct edge.
#
#   Scripts/check-build-graph.sh                  resolve every module's release runtime classpath
#   Scripts/check-build-graph.sh --graph <json>   check a pre-captured / fixture graph
#   Scripts/check-build-graph.sh --report <txt>   parse and check a captured `dependencies` report (fixtures)
#   TP_GRAPH_DUMP=<file> Scripts/check-build-graph.sh   also keep the captured graph, to inspect it
#
# Graph JSON shape (also what fixtures use):
#   { "modules": { "<module>": ["<group>:<artifact>", "project:<module>", ...] } }
#
# Rules (edit RULES below when the topology changes deliberately):
#   touchpoint-core, -core-android, -livesync, -ui  link neither Chime nor the Connect chat SDK
#   touchpoint-chat                            must not link Chime; MUST link the Connect chat SDK
#   touchpoint-voice                           MUST link Chime and its full media (screen sharing sends video), never the no-video-codecs build
#   touchpoint-voice-telecom                   as voice, and MUST link core-telecom (the opt-in background call)
#   touchpoint-development                     links Core and neither external SDK
#   every other module                         never touchpoint-development
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
GRAPH=""; REPORT=""
case "${1:-}" in
    --graph) GRAPH=$2 ;;
    --report) REPORT=$2 ;;
esac
CLEANUP=""
trap 'rm -f $CLEANUP' EXIT

if [ -z "$GRAPH" ]; then
    RAW=$(mktemp); GRAPH=$(mktemp); CLEANUP="$RAW $GRAPH"
    if [ -n "$REPORT" ]; then
        [ -f "$REPORT" ] || { echo "check-build-graph: no such report: $REPORT"; exit 2; }
        cp "$REPORT" "$RAW"
    else
        # One Gradle invocation; `--configuration` binds to the task before it. `--no-parallel` because
        # parallel execution interleaves the per-project reports, and the parser attributes each line
        # to the last project header it saw. JVM modules publish runtimeClasspath, Android libraries
        # releaseRuntimeClasspath.
        ARGS=""
        for m in "$ROOT"/touchpoint-*/; do
            name=$(basename "$m")
            case "$name" in touchpoint-e2e) continue ;; esac
            [ -f "$m/build.gradle.kts" ] || continue
            if grep -q 'android.library' "$m/build.gradle.kts"; then cfg=releaseRuntimeClasspath; else cfg=runtimeClasspath; fi
            ARGS="$ARGS :$name:dependencies --configuration $cfg"
        done
        # shellcheck disable=SC2086
        (cd "$ROOT" && ./gradlew -q --console=plain --no-parallel -Ptouchpoint.requireAndroid=true $ARGS) > "$RAW" 2>&1 \
            || { tail -20 "$RAW"; echo "check-build-graph: the dependency report failed"; exit 2; }
    fi
    python3 -I - "$RAW" "$GRAPH" <<'PY' || exit $?
import json, re, sys
raw, out = sys.argv[1], sys.argv[2]
modules, current = {}, None
# `dependencies` exits 0 even when a coordinate does not resolve; it prints it marked FAILED. A graph
# with a hole in it would satisfy every rule below, so an unresolved entry fails the gate outright.
unresolved = [l.strip() for l in open(raw) if re.search(r'\bFAILED\b', l)]
if unresolved:
    print("check-build-graph: unresolved dependencies:"); [print("  " + u) for u in unresolved[:10]]; sys.exit(2)
coord = re.compile(r'(?:^|[\s\\+|-])([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?=[:\s]|$)')
for line in open(raw):
    m = re.match(r"^Project '?:([A-Za-z0-9_\-]+)'?", line.strip())
    if m:
        current = m.group(1); modules[current] = set(); continue
    if current is None or not re.match(r'^[\s|\\+-]*[+\\]---', line):
        continue
    p = re.search(r"project '?:([A-Za-z0-9_\-]+)'?", line)
    if p:
        modules[current].add('project:' + p.group(1)); continue
    c = coord.search(line)
    if c:
        modules[current].add(c.group(1) + ':' + c.group(2))
json.dump({"modules": {k: sorted(v) for k, v in modules.items()}}, open(out, 'w'), indent=1)
PY
    [ -n "${TP_GRAPH_DUMP:-}" ] && cp "$GRAPH" "$TP_GRAPH_DUMP"
fi

python3 -I - "$GRAPH" <<'PY'
import json, sys
g = json.load(open(sys.argv[1]))["modules"]

def hits(module, patterns):
    """Entries of `module` matching any pattern. A pattern ending in ':' or '-' is a prefix; anything
    else is an exact coordinate — so the full media artifact does not match its no-video-codecs
    sibling, whose name it is a prefix of."""
    entries = g.get(module, [])
    return sorted(e for e in entries for p in patterns if (e.startswith(p) if p[-1] in ':-' else e == p))

CHIME = ('software.aws.chimesdk:',)
CHAT = ('software.aws.connect:amazon-connect-chat-android', 'aws.sdk.kotlin:', 'aws.smithy.kotlin:')
# The archived AWS Mobile SDK for Android, which the chat SDK left in 3.0: in no module's graph.
MOBILE_SDK = ('com.amazonaws:aws-android-sdk-',)
NO_VIDEO_MEDIA = ('software.aws.chimesdk:amazon-chime-sdk-media-no-video-codecs',)
DEV = ('project:touchpoint-development', 'software.aws.connect:touchpoint-development')
RULES = [
    # module,                   forbidden,                    required
    ('touchpoint-core',         CHIME + CHAT + DEV + MOBILE_SDK, ()),
    ('touchpoint-core-android', CHIME + CHAT + DEV + MOBILE_SDK, ()),
    ('touchpoint-livesync',     CHIME + CHAT + DEV + MOBILE_SDK, ()),
    # The UI kit both surfaces share: neither transport's SDK, so each product brings only its own.
    ('touchpoint-ui',           CHIME + CHAT + DEV + MOBILE_SDK, ()),
    ('touchpoint-chat',         CHIME + DEV + MOBILE_SDK,     ('software.aws.connect:amazon-connect-chat-android',)),
    ('touchpoint-voice',        CHAT + DEV + MOBILE_SDK + NO_VIDEO_MEDIA, ('software.aws.chimesdk:amazon-chime-sdk',
                                                               'software.aws.chimesdk:amazon-chime-sdk-media')),
    # The opt-in background call: voice's media rules, plus the core-telecom it exists to bring.
    ('touchpoint-voice-telecom', CHAT + DEV + MOBILE_SDK + NO_VIDEO_MEDIA, ('software.aws.chimesdk:amazon-chime-sdk',
                                                               'software.aws.chimesdk:amazon-chime-sdk-media',
                                                               'androidx.core:core-telecom')),
    # Debug-only, so it may link anything Core does and nothing a product does: a development backend
    # that pulled in a transport would make debug builds link what release builds do not.
    ('touchpoint-development',  CHIME + CHAT + MOBILE_SDK,    ('project:touchpoint-core',)),
]
ok = True
declared = {m for m, _, _ in RULES}
for module in sorted(set(g) - declared):
    # Every module gets a rule, so a new one cannot quietly link anything.
    print(f"FAIL {module}: no rule declared in RULES — add one before the module ships"); ok = False
for module, forbidden, required in RULES:
    if module not in g:
        print(f"FAIL {module}: missing from graph (no Android SDK, or the module was renamed?)"); ok = False; continue
    bad = hits(module, forbidden)
    missing = [r for r in required if not hits(module, (r,))]
    if bad:
        print(f"FAIL {module}: links forbidden {bad}"); ok = False
    if missing:
        print(f"FAIL {module}: expected to link {missing} — graph not read correctly?"); ok = False
    if not bad and not missing:
        print(f"ok   {module}: {len(g[module])} entries" + (f", links {list(required)}" if required else ""))
# Evidence the closure is transitive: chat reaches the AWS SDK for Kotlin only through the chat SDK.
via = hits('touchpoint-chat', ('aws.sdk.kotlin:connectparticipant',))
print(f"info touchpoint-chat transitive closure includes {via or 'nothing from aws.sdk.kotlin (fixture graph?)'}")
print("check-build-graph: " + ("ok" if ok else "FAILED"))
sys.exit(0 if ok else 1)
PY
