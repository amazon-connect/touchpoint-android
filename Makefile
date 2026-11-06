# Credential-free test executor for the Touchpoint Android SDK.
#
# `make check` is the bar before a push and before a release. Nothing here needs a device,
# a credential, or a network connection beyond Gradle's dependency fetch.
#
#   make check        everything below, in order — the pre-push bar. Needs the Android SDK (sdk.dir
#                     in local.properties, or ANDROID_HOME); a missing SDK fails rather than skipping.
#   make check-jvm    the JVM tier only (core, development, the e2e harness's offline tests), lint
#                     and scan: the fast inner loop, and what a machine without the Android SDK can run
#   make precommit    the fast subset wired into .git/hooks/pre-commit (lint + staged secret scan)
#   make connected    the instrumented tier on an attached device or emulator (adb devices)
#   make e2e          the live end-to-end run, where its harness is present (AWS credentials; E2E_ARGS)
#   make api          the public API against its reviewed dump (part of check)
#   make publish-local the release's artifacts under build/staging-deploy
#   make release-bundle the release's unsigned bundle for the Central Portal (BUNDLE=<zip>)
#   make hooks        install the pre-commit (precommit) and pre-push (check) hooks

GRADLE       ?= ./gradlew
GRADLE_FLAGS ?= --console=plain -Ptouchpoint.requireAndroid=true

.PHONY: api publish-local release-bundle check check-jvm precommit build test build-jvm connected e2e lint scan scan-staged graph manifests strings symbols scripts pins hooks clean

# The stamp records the commit the gates passed for. Any change to the tree — modified, staged or
# untracked, outside the gitignored scratchpad — marks it dirty, and a dirty stamp vouches for nothing.
check: build test lint scan graph manifests strings symbols scripts pins api
	@mkdir -p build && { git rev-parse HEAD; [ -z "$$(git status --porcelain --untracked-files=normal -- . ':!.agents')" ] || echo dirty; } > build/check-stamp
	@echo "✔ make check: all credential-free gates passed (stamped for pre-push)"

check-jvm: build-jvm lint scan
	@echo "✔ make check-jvm: JVM tier, lint and scan passed (the Android tier was not built)"

precommit: lint scan-staged
	@echo "✔ precommit gates passed"

# The instrumented tests are compiled here though `make connected` runs them: a device-only test that
# no longer compiles must not wait for a device to say so.
build:
	$(GRADLE) $(GRADLE_FLAGS) assemble assembleDebugAndroidTest

test:
	$(GRADLE) $(GRADLE_FLAGS) test

# The public API against its reviewed dump (api/*.api); `./gradlew updateKotlinAbi` records a change.
api:
	$(GRADLE) $(GRADLE_FLAGS) checkKotlinAbi

# The release's artifacts, unsigned, under build/staging-deploy. A release zips them with
# Scripts/release-bundle.sh (`make release-bundle`), and signs and uploads that elsewhere.
publish-local:
	$(GRADLE) $(GRADLE_FLAGS) publishAllPublicationsToLocalStagingRepository

#   make release-bundle BUNDLE=build/release/touchpoint-android-0.1.0.zip [GROUP=<dry-run group>]
release-bundle:
	sh Scripts/release-bundle.sh $(if $(GROUP),--group $(GROUP)) $(BUNDLE)

build-jvm:
	$(GRADLE) --console=plain :touchpoint-core:build :touchpoint-development:build $(if $(wildcard touchpoint-e2e),:touchpoint-e2e:build)

# The live end-to-end run, where its harness is present (needs AWS credentials). Not part of
# `check`, which must stay credential-free.
#   make e2e E2E_ARGS="--profile <name> --instance <id>"
e2e:
	$(GRADLE) --console=plain :touchpoint-e2e:run --args="$(E2E_ARGS)"

# Keystore, framework SQLite and resource resolution exist only on a device; the JVM tier cannot
# reach them. Not part of `check`, which must stay device-free.
connected:
	$(GRADLE) $(GRADLE_FLAGS) connectedDebugAndroidTest

lint:
	sh Scripts/lint.sh

scan:
	sh Scripts/scan-secrets.sh

scan-staged:
	sh Scripts/scan-secrets.sh --staged

graph:
	sh Scripts/check-build-graph.sh


manifests:
	sh Scripts/check-manifests.sh

strings:
	sh Scripts/check-string-resources.sh

symbols:
	sh Scripts/check-release-symbols.sh

scripts:
	sh Scripts/test-scripts.sh

# Host builds that already pin our dependencies. Network for Gradle's first fetch.
pins:
	sh Scripts/check-competing-pins.sh

hooks:
	sh Scripts/install-hooks.sh

clean:
	$(GRADLE) --console=plain clean
