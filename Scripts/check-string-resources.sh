#!/bin/sh
# String resources: prefixed, present and used.
#
#   1. Prefix. Library resources merge into the host's namespace, and a host resource with the
#      same name silently replaces ours. Every resource a module packages — any type, any file name,
#      from any source set — must carry the touchpoint_ prefix. Read from R.txt inside each built
#      release AAR, which lists exactly what ships; `resourcePrefix` alone is only an Android Lint
#      warning. Also: no name packaged by two of our modules.
#   2. Content. In every res/values*/*.xml of every source set: each <string>, <plurals>,
#      <string-array> and <item type="string"> has a value, and every translated name also exists in
#      the default values/ (a key only in values-fr/ never resolves for anyone else).
#   3. Bypass. In UI code (files importing Compose or android.widget/app/view), a literal holding
#      translatable text must not be what a text sink shows: Text/BasicText/ClickableText/
#      AnnotatedString arguments, append(…), setText(…), Toast.makeText(…), or a contentDescription/
#      stateDescription/text/label/placeholder/title/hint value. A literal with no letter outside its
#      templates (`"$count"`) is a composition and passes; `"Welcome back, $name!"` does not — Compose
#      shows it untranslated.
#
#   Scripts/check-string-resources.sh                       build release AARs, run all three
#   Scripts/check-string-resources.sh --resources <file>    content check of one values XML (fixtures)
#   Scripts/check-string-resources.sh --rtxt <file>         prefix check of one R.txt (fixtures)
#   Scripts/check-string-resources.sh --sources <path>      bypass check over one file or tree (fixtures)
# Exit 0 clean, 1 on any finding, 2 on a missing input or a failed build.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RES=""; RTXT=""; SRC=""
while [ $# -gt 0 ]; do
    case "$1" in
        --resources) RES=$2; shift 2 ;;
        --rtxt) RTXT=$2; shift 2 ;;
        --sources) SRC=$2; shift 2 ;;
        *) echo "unknown argument: $1"; exit 2 ;;
    esac
done
MODE=tree
[ -n "$RES$RTXT$SRC" ] && MODE=fixture

if [ "$MODE" = tree ]; then
    TASKS=""
    for m in "$ROOT"/touchpoint-*/; do
        grep -q 'android.library' "$m/build.gradle.kts" 2>/dev/null || continue
        rm -rf "$m/build/outputs/aar"
        TASKS="$TASKS :$(basename "$m"):assembleRelease"
    done
    # shellcheck disable=SC2086
    (cd "$ROOT" && ./gradlew -q --console=plain -Ptouchpoint.requireAndroid=true $TASKS) >/dev/null 2>&1 \
        || { echo "check-string-resources: release build failed"; exit 2; }
fi

python3 -I - "$ROOT" "$MODE" "$RES" "$RTXT" "$SRC" <<'PY'
import glob, io, os, re, sys, zipfile
import xml.etree.ElementTree as ET
root, mode, one_res, one_rtxt, one_src = sys.argv[1:6]
sys.path.insert(0, os.path.join(root, 'Scripts', 'lib'))
from kotlin_source import code_only, line_of, matching_paren, top_level_markers
ok = True

def fail(msg):
    global ok
    ok = False
    print("FAIL " + msg)

def need(path):
    if not os.path.exists(path):
        print(f"check-string-resources: no such input: {path}"); sys.exit(2)

# 1. Prefix, from R.txt -------------------------------------------------------------------------
def rtxt_names(text):
    """(type, name) pairs from R.txt lines `int <type> <name> <id>` / `int[] styleable <name> {…}`."""
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 3:
            yield parts[1], parts[2]

def prefixed(rtype, name):
    # Styleables are CamelCase by convention, and their attribute entries are Name_attr.
    return name.lower().startswith('touchpoint') if rtype == 'styleable' else name.startswith('touchpoint_')

def check_rtxt(label, text, owners):
    for rtype, name in rtxt_names(text):
        if not prefixed(rtype, name):
            fail(f"{label}: {rtype}/{name} lacks the touchpoint_ prefix")
        if owners is not None:
            key = (rtype, name)
            if key in owners and owners[key] != label:
                fail(f"{rtype}/{name} is packaged by both {owners[key]} and {label}")
            owners[key] = label

# 2. Content --------------------------------------------------------------------------------------
STRING_TAGS = ('string', 'plurals', 'string-array')

def entries(path):
    for el in ET.parse(path).getroot():
        if el.tag in STRING_TAGS or (el.tag == 'item' and el.attrib.get('type') == 'string'):
            yield el.tag, el.attrib.get('name', ''), ''.join(el.itertext()).strip()

def check_values(path):
    names = []
    try:
        items = list(entries(path))
    except ET.ParseError as e:
        fail(f"{os.path.relpath(path, root)}: not well-formed XML: {e}"); return names
    for tag, name, text in items:
        if not text:
            fail(f"{os.path.relpath(path, root)}: <{tag} name=\"{name}\"> has no value")
        names.append(name)
    return names

# 3. Bypass ---------------------------------------------------------------------------------------
UI_IMPORT = re.compile(r'^\s*import\s+(?:androidx\.compose\.|android\.widget\.|android\.app\.|android\.view\.)', re.MULTILINE)
CALL_SINKS = re.compile(r'(?:(?<![\w.])(?:Text|BasicText|ClickableText|AnnotatedString)|(?<![\w.])append|\.\s*setText|Toast\s*\.\s*makeText)\s*\(')
ASSIGN_SINKS = re.compile(r'(?<![\w.])(?:contentDescription|stateDescription|text|label|placeholder|title|hint)\s*=(?!=)')
DECLARED = re.compile(r'\b(?:val|var)\s+$')

def expression_end(code, start):
    """End of the expression starting at `start`: the first `,` `)` `;` or newline at depth 0."""
    depth = 0
    for k in range(start, len(code)):
        ch = code[k]
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            if depth == 0:
                return k
            depth -= 1
        elif depth == 0 and ch in ',;\n':
            return k
    return len(code)

def check_sources(base):
    files = [base] if base.endswith('.kt') else sorted(os.path.join(d, f) for d, _, fs in os.walk(base) for f in fs if f.endswith('.kt'))
    for f in files:
        src = open(f, encoding='utf-8', errors='replace').read()
        if not UI_IMPORT.search(src):
            continue
        code, literals = code_only(src)
        flagged = set()
        for m in CALL_SINKS.finditer(code):
            open_paren = m.end() - 1
            flagged.update(top_level_markers(code, open_paren + 1, matching_paren(code, open_paren)))
        for m in ASSIGN_SINKS.finditer(code):
            if DECLARED.search(code[max(0, m.start() - 8):m.start()]):
                continue                              # `val text = …` declares a value; it shows nothing
            flagged.update(top_level_markers(code, m.end(), expression_end(code, m.end())))
        for idx in sorted(flagged):
            lit = literals[idx]
            if lit.translatable:
                fail(f"{os.path.relpath(f, root)}:{lit.line}: user-facing literal bypasses string resources: \"{lit.text[:60]}\"")

if mode == 'fixture':
    if one_res:
        need(one_res); check_values(one_res)
    if one_rtxt:
        need(one_rtxt); check_rtxt(os.path.basename(one_rtxt), open(one_rtxt).read(), None)
    if one_src:
        need(one_src); check_sources(one_src)
else:
    owners = {}
    modules = [m for m in sorted(glob.glob(os.path.join(root, 'touchpoint-*')))
               if os.path.isfile(os.path.join(m, 'build.gradle.kts')) and 'android.library' in open(os.path.join(m, 'build.gradle.kts')).read()]
    for m in modules:
        name = os.path.basename(m)
        aar = os.path.join(m, 'build', 'outputs', 'aar', f'{name}-release.aar')
        if not os.path.isfile(aar):
            fail(f"{name}: no release AAR was built"); continue
        with zipfile.ZipFile(aar) as z:
            text = z.read('R.txt').decode() if 'R.txt' in z.namelist() else ''
        check_rtxt(name, text, owners)
    # Content, across every source set's values directories.
    defaults = {}
    for path in sorted(glob.glob(os.path.join(root, 'touchpoint-*', 'src', '*', 'res', 'values*', '*.xml'))):
        module = os.path.relpath(path, root).split(os.sep)[0]
        is_default = os.path.basename(os.path.dirname(path)) == 'values'
        for n in check_values(path):
            if is_default:
                defaults.setdefault(module, set()).add(n)
    for path in sorted(glob.glob(os.path.join(root, 'touchpoint-*', 'src', '*', 'res', 'values-*', '*.xml'))):
        module = os.path.relpath(path, root).split(os.sep)[0]
        try:
            for _, n, _ in entries(path):
                if n not in defaults.get(module, set()):
                    fail(f"{os.path.relpath(path, root)}: {n} has no default in values/")
        except ET.ParseError:
            pass
    if not defaults:
        fail("no string resources in any module — the shared ones live in touchpoint-core-android")
    for m in glob.glob(os.path.join(root, 'touchpoint-*', 'src', 'main')):
        if 'touchpoint-e2e' not in m:
            check_sources(m)
    if ok:
        print(f"ok   {len(modules)} module(s): every packaged resource prefixed, {sum(map(len, defaults.values()))} default string(s)")
print("check-string-resources: " + ("ok" if ok else "FAILED"))
sys.exit(0 if ok else 1)
PY
