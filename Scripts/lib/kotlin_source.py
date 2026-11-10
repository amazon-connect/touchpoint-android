"""Kotlin source tokenising shared by the lint and string-resource gates.

`code_only(src)` returns the source with comments, char literals and backticked names blanked, and
each string literal replaced by a marker `__STR<n>__` followed by the code of its `${…}` templates —
so a rule that scans the result sees code, never prose, while code hidden inside a template is still
seen. Newlines are preserved, so an offset in the result maps to the same line as in the source.

`literals` describes each string literal: its first line and whether it holds translatable text —
any letter outside its templates. `"$name"` holds none; `"Welcome back, $name!"` does, and in
Compose that string is shown untranslated, so the resource gate treats it as a literal.
"""
import bisect
import re


class Literal:
    def __init__(self, line, text):
        self.line = line
        self.text = text                      # the literal's own characters, templates removed
        self.translatable = re.search(r'[^\W\d_]', text) is not None


def code_only(src):
    n = len(src)
    literals = []

    def blank(s):
        return ''.join('\n' if c == '\n' else ' ' for c in s)

    def skip_char(j):
        """j is at a quote char `'`. Returns the index just past the char literal."""
        k = j + 1
        while k < n and src[k] not in "'\n":
            k += 2 if src[k] == '\\' else 1
        return k + 1

    def skip_comment(j):
        if src.startswith('//', j):
            k = src.find('\n', j)
            return n if k < 0 else k
        depth, k = 0, j                       # block comments nest
        while k < n:
            if src.startswith('/*', k):
                depth += 1; k += 2
            elif src.startswith('*/', k):
                depth -= 1; k += 2
                if depth == 0:
                    return k
            else:
                k += 1
        return k

    def end_of_string(j, kept, own):
        """j is at the opening quote(s). Returns the index past the literal; template code goes to
        `kept`, the literal's own characters to `own`."""
        raw = src.startswith('"""', j)
        k = j + (3 if raw else 1)
        while k < n:
            if raw and src.startswith('"""', k):
                k += 3
                while k < n and src[k] == '"':
                    own.append('"'); k += 1
                return k
            if not raw:
                if src[k] == '"':
                    return k + 1
                if src[k] == '\\':
                    own.append(src[k:k + 2]); k += 2
                    continue
                if src[k] == '\n':
                    return k
            if src.startswith('${', k):
                k = end_of_template(k, kept)
                continue
            if src[k] == '$' and k + 1 < n and (src[k + 1].isalpha() or src[k + 1] == '_'):
                m = re.match(r'\$[A-Za-z_]\w*', src[k:])
                kept.append(m.group(0)[1:]); k += len(m.group(0))
                continue
            own.append(src[k]); k += 1
        return k

    def end_of_template(j, kept):
        """j is at `${`. Appends the expression's code — nested literals, chars and comments
        blanked — to `kept`; returns the index past the closing brace."""
        depth, k, expr = 1, j + 2, []
        while k < n:
            ch = src[k]
            if ch == '"':
                e = end_of_string(k, kept, [])
                expr.append(' '); k = e; continue
            if ch == "'":
                e = skip_char(k); expr.append(' '); k = e; continue
            if src.startswith('//', k) or src.startswith('/*', k):
                e = skip_comment(k); expr.append(' '); k = e; continue
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    break
            expr.append(ch); k += 1
        kept.append(''.join(expr).replace('\n', ' '))
        return k + 1

    out, i = [], 0
    line_starts = [0] + [m.end() for m in re.finditer('\n', src)]
    while i < n:
        c = src[i]
        if src.startswith('//', i) or src.startswith('/*', i):
            j = skip_comment(i); out.append(blank(src[i:j])); i = j
        elif c == '"':
            kept, own = [], []
            j = end_of_string(i, kept, own)
            idx = len(literals)
            literals.append(Literal(bisect.bisect_right(line_starts, i), ''.join(own)))
            out.append(f' __STR{idx}__ ' + ' '.join(kept) + ' ' + '\n' * src.count('\n', i, j)); i = j
        elif c == "'":
            j = skip_char(i); out.append(blank(src[i:j])); i = j
        elif c == '`':
            j = src.find('`', i + 1)
            j = n - 1 if j < 0 else j
            out.append(blank(src[i:j + 1])); i = j + 1
        else:
            out.append(c); i += 1
    return ''.join(out), literals


def line_of(text, offset):
    return text.count('\n', 0, offset) + 1


def matching_paren(code, open_index):
    """Index of the `)` matching the `(` at open_index, or len(code)."""
    depth = 0
    for k in range(open_index, len(code)):
        if code[k] in '([{':
            depth += 1
        elif code[k] in ')]}':
            depth -= 1
            if depth == 0:
                return k
    return len(code)


# A `{` after these yields its block's value: `when (s) { 0 -> "A" }`, `if (c) { "A" } else { "B" }`,
# `n?.let { "Hi $it" }`. Any other block — `Modifier.semantics { testTag = "x" }` — yields nothing shown.
_VALUE_BLOCK_KEYWORDS = {'when', 'if', 'else', 'try', 'catch', 'finally', 'let', 'run', 'with'}


def _opens_value_block(code, brace):
    k = brace - 1
    while k >= 0 and code[k].isspace():
        k -= 1
    if k >= 0 and code[k] == ')':                 # `when (s) {`, `if (c) {`, `with(x) {`: the word before `(`
        depth = 0
        while k >= 0:
            if code[k] == ')':
                depth += 1
            elif code[k] == '(':
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        k -= 1
        while k >= 0 and code[k].isspace():
            k -= 1
    if k >= 0 and code[k] == '>' and code[k - 1:k + 1] == '->':
        return True                               # a when branch `0 -> { "A" }`
    m = re.search(r'(\w+)$', code[:k + 1])
    return m is not None and m.group(1) in _VALUE_BLOCK_KEYWORDS


def top_level_markers(code, start, end):
    """`__STR<n>__` indices that an argument list or expression in code[start:end] yields directly:
    those at bracket depth 0, and those inside a block whose value is the expression's (a `when` or
    `if` branch, a `let`/`run` lambda) — not ones passed to a nested call such as testTag(…)."""
    found, stack = [], []                         # stack: True for a transparent (value-yielding) block
    for m in re.finditer(r'[()\[\]{}]|__STR(\d+)__', code[start:end]):
        tok = m.group(0)
        if tok in '([':
            stack.append(False)
        elif tok == '{':
            stack.append(_opens_value_block(code, start + m.start()))
        elif tok in ')]}':
            if stack:
                stack.pop()
        elif all(stack):
            found.append(int(m.group(1)))
    return found
