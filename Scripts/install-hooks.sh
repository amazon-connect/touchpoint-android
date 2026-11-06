#!/bin/sh
# Installs two hooks: pre-commit runs the lint and the staged secret scan; pre-push runs
# the full `make check` so nothing reaches the shared repository without every gate passing.
# Run once per clone: `make hooks`. Git does not version hooks, so this is per-developer.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOOK="$ROOT/.git/hooks/pre-commit"
[ -d "$ROOT/.git/hooks" ] || { echo "install-hooks: not a git checkout"; exit 1; }
cat > "$HOOK" <<'HOOK'
#!/bin/sh
# Installed by Scripts/install-hooks.sh. Fast gates only; `make check` is the full bar.
cd "$(git rev-parse --show-toplevel)" && make --no-print-directory precommit
HOOK
chmod +x "$HOOK"
echo "install-hooks: wrote ${HOOK#$ROOT/}"
PUSH="$ROOT/.git/hooks/pre-push"
cat > "$PUSH" <<'HOOK'
#!/bin/sh
# Installed by Scripts/install-hooks.sh. Full credential-free bar before anything leaves this machine.
# `make check` verifies the working tree, which is HEAD, so the hook refuses to vouch for any other
# commit being pushed. If HEAD is the commit `make check` stamped and the tree is clean, the bar has
# already been met for exactly these bytes and re-running it proves nothing.
cd "$(git rev-parse --show-toplevel)" || exit 1
head=$(git rev-parse HEAD)
while read -r local_ref local_sha remote_ref remote_sha; do
    case "$local_sha" in 0000000000000000000000000000000000000000) continue ;; esac   # a deletion
    if [ "$local_sha" != "$head" ]; then
        echo "pre-push: $local_ref is not HEAD; make check verifies HEAD only — check it out and push from there"
        exit 1
    fi
done
if [ -f build/check-stamp ] && [ "$(cat build/check-stamp)" = "$head" ] \
    && [ -z "$(git status --porcelain --untracked-files=normal -- . ':!.agents')" ]; then
    echo "pre-push: make check already passed for $(git rev-parse --short HEAD); skipping"
    exit 0
fi
make --no-print-directory check
HOOK
chmod +x "$PUSH"
echo "install-hooks: wrote ${PUSH#$ROOT/}"
