#!/bin/sh
# Lint gate. Two rules:
#   no-crash        the library must not crash its host: no `!!`, no unsafe `as` cast, no `TODO()`,
#                   `error()`, `require`/`check` (and their NotNull forms, called or referenced), no
#                   `assert` (off in a release build, so it checks nothing there), no process exit,
#                   and no throwing of a standard exception type. Each is the Kotlin form of a rule against
#                   an unchecked failure on host
#                   input. Throw a TouchpointException instead, which the host can catch and the docs
#                   can name.
#   scaled-metrics  Compose code must not carry absolute typographic or spacing values: a literal
#                   `12.dp`, `(12).dp`, `14.sp`, `Dp(12f)` or `TextUnit(14f, …)`. Dimensions come from
#                   the metrics type, which scales with the user's font scale; the one file that
#                   declares those multipliers is exempt (F9_EXEMPT). Applies to files importing
#                   androidx.compose; zero is exempt, because "no gap" carries no size to scale.
#
#   Scripts/lint.sh [path ...]     default: every library module's src/main (tests are excluded)
#
# Source is tokenised by Scripts/lib/kotlin_source.py, not grepped: comments, literals and backticked
# names are blanked, while code inside `${…}` templates is kept. Rules run over the whole file, so a
# construct split across lines is still one match.
#
# A finding that is genuinely not a crash path (Compose's semantics `error(…)`, say) is suppressed on
# its own line with `// touchpoint-lint: allow <rule> — <reason>`; the reason is the review item.
# Exit 0 clean, 1 on any finding, 2 when there is nothing to lint or a path does not exist.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ $# -eq 0 ]; then
    for m in "$ROOT"/touchpoint-*/; do
        case "$m" in *touchpoint-e2e/) continue ;; esac   # the harness is not library code
        [ -d "$m/src/main" ] && set -- "$@" "$m/src/main"
    done
fi

python3 -I - "$ROOT" "$@" <<'PY'
import os, re, sys
root, paths = sys.argv[1], sys.argv[2:]
sys.path.insert(0, os.path.join(root, 'Scripts', 'lib'))
from kotlin_source import code_only, line_of

QUAL = r'(?:(?<![\w.])|(?<=\bkotlin\.))'          # a bare call, or one qualified with `kotlin.`
N9 = [
    ('nonnull', 'non-null assertion', re.compile(r'!!')),
    ('cast', 'unsafe cast', re.compile(r'\bas\b(?!\?)')),
    ('todo', 'TODO()', re.compile(QUAL + r'TODO\s*\(')),
    ('error', 'error()', re.compile(QUAL + r'error\s*\(')),
    ('precondition', 'precondition', re.compile(QUAL + r'(?:require|requireNotNull|check|checkNotNull|assert)\s*[({]')),
    ('precondition', 'precondition reference', re.compile(r'::\s*(?:require|requireNotNull|check|checkNotNull|assert|error|TODO)\b')),
    ('exit', 'process exit', re.compile(r'(?:(?<![\w.])|(?<=\bsystem\.))exitProcess\s*\(|\bSystem\s*\.\s*exit\s*\(|\.\s*halt\s*\(')),
    ('throw', 'throws a standard exception', re.compile(
        r'\bthrow\s+(?:[a-z_]\w*\s*\.\s*)*(?:IllegalStateException|IllegalArgumentException|NotImplementedError|'
        r'UnsupportedOperationException|NullPointerException|ClassCastException|IndexOutOfBoundsException|'
        r'NoSuchElementException|ArithmeticException|RuntimeException|Exception|Error|AssertionError|Throwable)\s*\(')),
]
NUMBER = r'(0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)[fFL]?'
F9 = [
    ('f9', 'literal dimension', re.compile(r'(?<![\w.])-?' + NUMBER + r'\s*\.\s*(?:dp|sp|em)\b')),
    ('f9', 'literal dimension', re.compile(r'\(\s*-?' + NUMBER + r'\s*\)\s*\.\s*(?:dp|sp|em)\b')),
    ('f9', 'literal Dp', re.compile(r'\bDp\(\s*(?:value\s*=\s*)?-?' + NUMBER + r'\s*\)')),
    ('f9', 'literal text unit', re.compile(r'\bTextUnit\(\s*(?:value\s*=\s*)?-?' + NUMBER)),
]
DECLARATION = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$')
# Exact relative paths: a file merely *named* like the metrics file must not inherit the exemption.
F9_EXEMPT = ('touchpoint-ui/src/main/kotlin/com/amazon/connect/touchpoint/chat/ui/TouchpointMetrics.kt',)
SUPPRESS = re.compile(r'touchpoint-lint:\s*allow\s+([a-z0-9-]+)')

def line_comment(line):
    """The `//` comment on a line, so a marker quoted inside a string literal suppresses nothing."""
    k, quote = 0, False
    while k < len(line):
        c = line[k]
        if quote:
            k += 2 if c == '\\' else 1
            if c == '"':
                quote = False
            continue
        if c == '"':
            quote = True
        elif c == "'":
            k += 3 if line[k + 1:k + 2] == '\\' else 2
        elif line.startswith('//', k):
            return line[k:]
        k += 1
    return ''

def number(text):
    t = text.replace('_', '')
    return int(t, 16) if t.lower().startswith('0x') else float(t)

def kotlin_files(ps):
    for p in ps:
        if os.path.isdir(p):
            for d, _, fs in sorted(os.walk(p)):
                for f in sorted(fs):
                    if f.endswith('.kt'):
                        yield os.path.join(d, f)
        elif p.endswith('.kt') and os.path.isfile(p):
            yield p
        else:
            print(f"lint: no such Kotlin file or directory: {p}"); sys.exit(2)

files = list(kotlin_files(paths))
if not files:
    print("lint: nothing to lint (no .kt files under the given paths)"); sys.exit(2)

findings = []
for path in files:
    with open(path, encoding='utf-8', errors='replace') as fh:
        src = fh.read()
    raw_lines = src.split('\n')
    code, _ = code_only(src)
    # Imports are not code: `import a.B as C` is an alias, not a cast.
    code = re.sub(r'^[ \t]*import\s[^\n]*', lambda m: ' ' * len(m.group(0)), code, flags=re.MULTILINE)
    rel = os.path.relpath(os.path.abspath(path), root).replace(os.sep, '/')
    rules = list(N9)
    if re.search(r'^\s*import\s+androidx\.compose\.', src, re.MULTILINE) and rel not in F9_EXEMPT:
        rules += F9
    seen = set()
    for rule, name, rx in rules:
        for m in rx.finditer(code):
            if rule == 'f9' and number(m.group(1)) == 0:
                continue
            if rule in ('error', 'precondition', 'todo') and DECLARATION.search(code[max(0, m.start() - 60):m.start()]):
                continue                              # declaring a member named error/check is not calling it
            n = line_of(code, m.start())
            text = raw_lines[n - 1] if n <= len(raw_lines) else ''
            s = SUPPRESS.search(line_comment(text))
            if s and s.group(1) == rule:
                continue
            if (n, rule) in seen:
                continue
            seen.add((n, rule))
            findings.append((path, n, ('scaled-metrics: ' if rule == 'f9' else 'no-crash: ') + name, text.strip()))

for path, n, kind, text in sorted(findings):
    print(f"{path}:{n}: {kind}: {text}")
if findings:
    print(f"lint: {len(findings)} violation(s)")
    sys.exit(1)
print(f"lint: ok ({len(files)} file(s))")
PY
