#!/bin/sh
# Exercises every gate script against the fixtures in Tests/Fixtures, so a detector that silently
# stopped detecting fails here rather than waving bad code through. Credential-free and device-free;
# the cases marked "real" run the gate over the actual tree, which needs the Android SDK.
#
# A must-fail case passes only when the gate exits 1 *and* names the expected rule: a gate that
# crashed (exit 2) or a fixture that went missing must not read as "detected".
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
S="$ROOT/Scripts"
F="$ROOT/Tests/Fixtures"
fail=0
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

pass() { printf '  ok   %s\n' "$1"; }
flunk() { printf '  FAIL %s\n' "$1"; fail=1; }

expect_pass() { # expect_pass <description> <command...>
    desc=$1; shift
    out=$("$@" 2>&1); got=$?
    [ "$got" = 0 ] && pass "$desc" || flunk "$desc (expected exit 0, got $got): $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
}

expect_fail() { # expect_fail <rule text> <description> <command...>
    rule=$1; desc=$2; shift 2
    out=$("$@" 2>&1); got=$?
    if [ "$got" = 1 ] && printf '%s' "$out" | grep -q -- "$rule"; then pass "$desc"; else flunk "$desc (expected exit 1 naming '$rule', got exit $got)"; fi
}

expect_exit() { # expect_exit <code> <description> <command...>
    want=$1; desc=$2; shift 2
    "$@" >/dev/null 2>&1; got=$?
    [ "$got" = "$want" ] && pass "$desc" || flunk "$desc (expected exit $want, got $got)"
}

expect_count() { # expect_count <n> <pattern> <description> <command...>
    want=$1; pattern=$2; desc=$3; shift 3
    got=$("$@" 2>&1 | grep -c -- "$pattern")
    [ "$got" -eq "$want" ] && pass "$desc" || flunk "$desc (expected $want findings, got $got)"
}

aar() { # aar <manifest.xml> <out.aar> — a fixture AAR holding just that manifest
    python3 -I -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[2], "w"); z.write(sys.argv[1], "AndroidManifest.xml"); z.close()' "$1" "$2"
}

echo "lint.sh (no-crash, scaled-metrics)"
expect_pass "C1 clean fixture passes (comments, literals, templates, aliases, as?, declarations, suppressions)" sh "$S/lint.sh" "$F/lint/clean.kt"
expect_fail "non-null assertion" "C2 !! flagged" sh "$S/lint.sh" "$F/lint/nonnull.kt"
expect_fail "non-null assertion" "C2b a marker quoted in a string suppresses nothing" sh "$S/lint.sh" "$F/lint/suppress-in-literal.kt"
expect_fail "non-null assertion" "C3 !! inside a string template flagged" sh "$S/lint.sh" "$F/lint/template.kt"
expect_fail "non-null assertion" "C4 !! after a '}' char literal inside a template flagged" sh "$S/lint.sh" "$F/lint/template-char.kt"
expect_fail "non-null assertion" "C5 !! after a '\"' char literal inside a template flagged" sh "$S/lint.sh" "$F/lint/template-quote-char.kt"
expect_fail "unsafe cast" "C6 unsafe as-cast flagged" sh "$S/lint.sh" "$F/lint/cast.kt"
expect_fail "TODO()" "C7 TODO() flagged" sh "$S/lint.sh" "$F/lint/todo.kt"
expect_fail "error()" "C8 error() flagged" sh "$S/lint.sh" "$F/lint/error.kt"
expect_fail "precondition" "C9 require() flagged" sh "$S/lint.sh" "$F/lint/precondition.kt"
expect_fail "precondition" "C9b assert() flagged, as assert is banned (it is off in release, so a check that only asserts checks nothing)" sh "$S/lint.sh" "$F/lint/assert.kt"
expect_fail "precondition" "C10 kotlin.require() flagged" sh "$S/lint.sh" "$F/lint/qualified.kt"
expect_fail "precondition reference" "C11 ::requireNotNull flagged" sh "$S/lint.sh" "$F/lint/reference.kt"
expect_fail "process exit" "C12 exitProcess flagged" sh "$S/lint.sh" "$F/lint/exit.kt"
expect_fail "throws a standard exception" "C13 throw IllegalArgumentException flagged" sh "$S/lint.sh" "$F/lint/throw.kt"
expect_fail "throws a standard exception" "C14 throw java.lang.IllegalStateException flagged" sh "$S/lint.sh" "$F/lint/throw-qualified.kt"
expect_fail "throws a standard exception" "C15 throw split across lines flagged" sh "$S/lint.sh" "$F/lint/throw-multiline.kt"
# A count, not just non-zero: asserting "some finding" would stay green while one form silently
# stopped being detected.
expect_count 14 "scaled-metrics: " "C16 all 14 literal-dimension forms flagged" sh "$S/lint.sh" "$F/lint/f9-literals.kt"
expect_pass "C17 metrics, zero and strings pass" sh "$S/lint.sh" "$F/lint/f9-clean.kt"
expect_pass "C18 scaled-metrics applies to Compose files only" sh "$S/lint.sh" "$F/lint/f9-not-compose.kt"
expect_exit 2 "C19 a missing path is an error, not a pass" sh "$S/lint.sh" "$F/lint/no-such-file.kt"
mkdir -p "$WORK/empty"
expect_exit 2 "C20 nothing to lint is an error, not a pass" sh "$S/lint.sh" "$WORK/empty"
expect_pass "C21 real: every library src/main passes" sh "$S/lint.sh"

echo "scan-secrets.sh"
expect_pass "D1 clean fixture passes" sh "$S/scan-secrets.sh" "$F/scan/clean.txt"
expect_count 8 "secret-scan: " "D2 must-fail fixture: all 8 findings" sh "$S/scan-secrets.sh" "$F/scan/must-fail.txt"
# The internal patterns exist only in internal checkouts; a public tree runs D2 alone.
if [ -f "$S/internal/scan-patterns.txt" ]; then
    expect_count 11 "secret-scan: " "D2i internal must-fail fixture: all 11 findings" sh "$S/scan-secrets.sh" "$S/internal/scan-must-fail.txt"
    expect_count 11 "secret-scan: " "D2n TP_SCAN_PATTERNS loads the internal patterns from elsewhere" env TP_SCAN_PATTERNS="$S/internal/scan-patterns.txt" sh "$S/scan-secrets.sh" "$S/internal/scan-must-fail.txt"
    # One finding per line, first pattern wins: the fixture's account page (its line 6) must reach
    # its own rule, not the link rule. Named by line: this script is public, the fixture is not.
    expect_fail "scan-must-fail.txt:6: secret-scan: AWS account id" "D2a the internal account page is reported as an account id" sh "$S/scan-secrets.sh" "$S/internal/scan-must-fail.txt"
fi
expect_exit 2 "D2m a named pattern file that is missing is an error" env TP_SCAN_PATTERNS="$WORK/no-such-patterns.txt" sh "$S/scan-secrets.sh" "$F/scan/clean.txt"
printf 'broken\t(unclosed\n' > "$WORK/bad-patterns.txt"
expect_exit 2 "D2r a pattern file with an invalid regex is an error, not a finding" env TP_SCAN_PATTERNS="$WORK/bad-patterns.txt" sh "$S/scan-secrets.sh" "$F/scan/clean.txt"
expect_exit 2 "D3 a missing explicit path is an error" sh "$S/scan-secrets.sh" "$F/scan/no-such-file.txt"
# The staged scan must read the index: a key staged and then cleaned from the working copy is still
# about to be committed.
# The fake key is assembled at run time so this script does not itself trip the tree scan.
FAKE_KEY="AKIA$(printf 'IOSFODNN7EXAMPLE')"
# Under a git hook (pre-push), git exports GIT_DIR and GIT_INDEX_FILE: left set, the fixture repo's
# `git add` would stage into the real repository's index. Its own repo, whatever runs this.
unset GIT_DIR GIT_INDEX_FILE GIT_WORK_TREE GIT_PREFIX GIT_COMMON_DIR
(cd "$WORK" && git init -q staged && cd staged && printf 'key: %s\n' "$FAKE_KEY" > a.txt && git add a.txt && printf 'clean now\n' > a.txt)
expect_fail "AWS access key id" "D4 --staged reads the staged blob, not the working copy" env TP_SCAN_ROOT="$WORK/staged" sh "$S/scan-secrets.sh" --staged
(cd "$WORK/staged" && printf 'key: %s\n' "$FAKE_KEY" > untracked.txt)
expect_fail "AWS access key id" "D5 the tree scan includes untracked files" env TP_SCAN_ROOT="$WORK/staged" sh "$S/scan-secrets.sh"
expect_pass "D6 real: --staged on the current index passes" sh "$S/scan-secrets.sh" --staged
expect_pass "D7 real: whole-tree scan, honouring the allowlist" sh "$S/scan-secrets.sh"

echo "check-build-graph.sh"
expect_pass "E1 clean fixture graph passes" sh "$S/check-build-graph.sh" --graph "$F/graph/clean.json"
expect_fail "links forbidden" "E2 chat linking Chime fails" sh "$S/check-build-graph.sh" --graph "$F/graph/chat-links-chime.json"
expect_fail "links forbidden" "E3 voice linking the audio-only media build fails" sh "$S/check-build-graph.sh" --graph "$F/graph/voice-no-video-media.json"
expect_fail "links forbidden" "E4 Live Sync linking the chat SDK's AWS SDK for Kotlin fails" sh "$S/check-build-graph.sh" --graph "$F/graph/livesync-links-chat.json"
expect_fail "links forbidden" "E5 core depending on touchpoint-development fails" sh "$S/check-build-graph.sh" --graph "$F/graph/core-links-development.json"
expect_fail "links forbidden" "E6a chat linking the archived AWS Mobile SDK fails" sh "$S/check-build-graph.sh" --graph "$F/graph/chat-links-mobile-sdk.json"
expect_fail "no rule declared" "E6 a module with no rule fails" sh "$S/check-build-graph.sh" --graph "$F/graph/unruled-module.json"
expect_exit 2 "E7 an unresolved dependency fails the gate" sh "$S/check-build-graph.sh" --report "$F/graph/report-unresolved.txt"
expect_fail "links forbidden" "E7b the development backend linking the chat SDK fails" sh "$S/check-build-graph.sh" --graph "$F/graph/development-links-chat.json"
expect_pass "E8 real: resolved release classpaths pass" sh "$S/check-build-graph.sh"

echo "check-manifests.sh"
aar "$F/manifests/clean.xml" "$WORK/clean.aar"
aar "$F/manifests/permission.xml" "$WORK/permission.aar"
aar "$F/manifests/application.xml" "$WORK/application.aar"
aar "$F/manifests/removal.xml" "$WORK/removal.aar"
expect_pass "G1 an empty packaged manifest passes" sh "$S/check-manifests.sh" --aar "$WORK/clean.aar" --module touchpoint-ui
expect_fail "merges into the host" "G2 a packaged permission fails, whichever source set declared it" sh "$S/check-manifests.sh" --aar "$WORK/permission.aar" --module touchpoint-ui
expect_fail "merges into the host" "G3 an <application> element fails" sh "$S/check-manifests.sh" --aar "$WORK/application.aar" --module touchpoint-ui
expect_pass "G4 an allowlisted removal passes" sh "$S/check-manifests.sh" --aar "$WORK/removal.aar" --module touchpoint-voice
expect_fail "merges into the host" "G5 a removal not in REMOVALS fails" sh "$S/check-manifests.sh" --aar "$WORK/removal.aar" --module touchpoint-ui
expect_pass "G6 the reviewed inherited declarations pass" sh "$S/check-manifests.sh" --deps "$F/manifests/deps/chat-expected.xml" --module touchpoint-chat
expect_fail "not in INHERITED" "G7 a newly inherited permission fails" sh "$S/check-manifests.sh" --deps "$F/manifests/deps/new-permission.xml" --module touchpoint-chat
expect_fail "no dependency declares" "G8 a stale allowlist entry fails" sh "$S/check-manifests.sh" --deps "$F/manifests/clean.xml" --module touchpoint-voice
expect_exit 2 "G9 a missing input is an error" sh "$S/check-manifests.sh" --aar "$WORK/no-such.aar" --module touchpoint-chat
expect_pass "G10 real: own and inherited manifests of every module pass" sh "$S/check-manifests.sh"

echo "check-string-resources.sh"
expect_pass "H1 prefixed strings and plurals pass" sh "$S/check-string-resources.sh" --resources "$F/strings/clean.xml"
expect_fail "has no value" "H2 an empty value fails" sh "$S/check-string-resources.sh" --resources "$F/strings/empty-value.xml"
expect_fail "has no value" "H3 an empty <item type=string> fails" sh "$S/check-string-resources.sh" --resources "$F/strings/item-empty.xml"
expect_pass "H4 a fully prefixed R.txt passes, styleables included" sh "$S/check-string-resources.sh" --rtxt "$F/rtxt/clean.txt"
expect_count 2 "lacks the touchpoint_ prefix" "H5 every unprefixed packaged resource is named" sh "$S/check-string-resources.sh" --rtxt "$F/rtxt/unprefixed.txt"
expect_count 13 "bypasses string resources" "H6 all 13 bypass literals flagged" sh "$S/check-string-resources.sh" --sources "$F/strings/bypass.kt"
expect_pass "H7 stringResource, compositions, nested calls and blocks, builders, KDoc and plain values pass" sh "$S/check-string-resources.sh" --sources "$F/strings/resource-ok.kt"
expect_exit 2 "H8 a missing input is an error" sh "$S/check-string-resources.sh" --sources "$F/strings/no-such.kt"
expect_pass "H9 real: the tree's packaged resources and sources pass" sh "$S/check-string-resources.sh"

echo "check-release-symbols.sh"
# The negative test must never silently become a no-op: build a jar that does contain the class.
mkdir -p "$WORK/syn/src/com/amazon/connect/touchpoint/development"
printf 'package com.amazon.connect.touchpoint.development;\npublic class DevelopmentBackend {}\n' \
    > "$WORK/syn/src/com/amazon/connect/touchpoint/development/DevelopmentBackend.java"
if javac -d "$WORK/syn/classes" "$WORK/syn/src/com/amazon/connect/touchpoint/development/DevelopmentBackend.java" 2>/dev/null \
    && jar cf "$WORK/syn/development.jar" -C "$WORK/syn/classes" . 2>/dev/null; then
    expect_fail "DevelopmentBackend" "I1 a jar carrying DevelopmentBackend fails" sh "$S/check-release-symbols.sh" --jar "$WORK/syn/development.jar"
else
    flunk "I1 could not build the synthetic jar — the detector is unverified"
fi
expect_exit 2 "I2 a missing artifact is an error" sh "$S/check-release-symbols.sh" --jar "$WORK/no-such.jar"
expect_pass "I3 real: no release artifact carries DevelopmentBackend" sh "$S/check-release-symbols.sh"

[ "$fail" = 0 ] && echo "all script checks passed" || { echo "script checks FAILED"; exit 1; }
