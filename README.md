# build-tools

Shared CI workflows and common Gradle build logic for aaatechnology Android app
repos. The workflows were extracted after the same GitHub Actions storage-quota bug
was found independently copy-pasted across six app repos; the Gradle plugin
followed the same pattern after the same versioning/Jacoco/signing boilerplate
turned up duplicated in every app's own `build.gradle.kts` - fixing each once here
means neither can silently drift out of sync per-repo again.

## Workflows

### `ci-build-test.yml` (reusable)

Builds the debug APK, runs unit tests + Jacoco coverage, and distributes the APK via
Firebase App Distribution. Call it from an app repo's own workflow like:

```yaml
name: CI - Build, Test & Debug APK

on:
  pull_request:
    branches: [ develop, main ]
    paths-ignore:
      - '**.md'
  # Optional: lets anyone retrigger this build by commenting "build-it" on a PR.
  # The job-level `if:` below is what actually filters this down to that keyword -
  # every other issue_comment event falls through without invoking the reusable
  # workflow at all. Omit this whole block if you don't want comment-triggered
  # rebuilds for a given app repo.
  issue_comment:
    types: [ created ]
  workflow_dispatch:

concurrency:
  # Keyed on the PR/issue number for issue_comment events (github.ref is just the
  # default branch there, which would otherwise put every PR's "build-it" comment
  # into the same concurrency group and cancel each other out).
  group: ci-${{ github.workflow }}-${{ github.event.issue.number || github.ref }}
  cancel-in-progress: true

jobs:
  build:
    if: >
      github.event_name != 'issue_comment' ||
      (github.event.issue.pull_request != null &&
       contains(github.event.comment.body, 'build-it'))
    # Required on the caller if this repo's default Actions token permissions
    # (Settings -> Actions -> General -> Workflow permissions) aren't "Read and
    # write" - without it, the reusable workflow's own contents:write/
    # pull-requests:write request gets capped down to the repo default and the
    # call fails at startup with "Invalid workflow file" / "The nested job ...
    # is requesting ..., but is only allowed ...". Harmless to include even when
    # the repo default is already permissive enough.
    permissions:
      contents: write
      pull-requests: write
    uses: aaatechnology/build-tools/.github/workflows/ci-build-test.yml@main
    with:
      app_display_name: Age Calculator
      auto_merge_to_develop: true
    secrets: inherit
```

Required secrets on the calling repo: `FIREBASE_APP_ID`, `CREDENTIAL_FILE_CONTENT`.

`auto_merge_to_develop` (optional, default `false`) queues GitHub's native auto-merge
on any PR targeting `develop` once this job succeeds. It does **not** bypass review -
auto-merge only actually merges once every branch-protection requirement on `develop`
is also satisfied (e.g. an approving review). Two prerequisites on the calling repo,
both admin-only settings:

- Settings → General → Pull Requests → **Allow auto-merge** must be checked, or
  `gh pr merge --auto` fails outright.
- `develop` needs a branch protection rule requiring at least one approving review
  and this workflow's status check (`build / build`), or auto-merge has nothing to
  wait for and merges as soon as the build passes with no review at all.

**Comment-triggered rebuilds:** the `issue_comment`/`if:` block above is entirely the
calling repo's responsibility - this reusable workflow itself doesn't declare or filter
on any trigger (it's `workflow_call`-only), it just needs to resolve the same PR head/
base SHAs a `pull_request` event gets for free, since `issue_comment` only carries the
issue/PR number. See the "Resolve PR context" step in `ci-build-test.yml` for how that
works. A calling repo that omits the `issue_comment` trigger simply never exercises
this path - no changes needed there to stay on the old pull_request/workflow_dispatch-
only behavior.

### `cd-internal-testing.yml` (reusable)

Builds a signed release App Bundle + APK, uploads the AAB to Play Store Internal
Testing, uploads the release APK to Firebase App Distribution, and - only once that
upload is confirmed successful - creates the git tag + draft GitHub release that
records the shipped version. Call it on push to `main`:

```yaml
name: CD - Deploy to Internal Testing

on:
  push:
    branches: [ main ]
  workflow_dispatch:

# No cancel-in-progress: a Play Store / Firebase upload should never be killed
# mid-flight. This just queues a second trigger instead of letting two releases
# run concurrently against the same track.
concurrency:
  group: release-play-store-${{ github.ref }}

jobs:
  release:
    # See the note under ci-build-test.yml's example above - required unless this
    # repo's default Actions token permissions are already "Read and write".
    permissions:
      contents: write
      pull-requests: write
    uses: aaatechnology/build-tools/.github/workflows/cd-internal-testing.yml@main
    with:
      app_display_name: Age Calculator
      package_name: com.arun.agecalculator
      play_internal_testing_link: ${{ vars.PLAY_INTERNAL_TESTING_LINK }}
    secrets: inherit
```

Required secrets on the calling repo: `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`,
`PLAY_SERVICE_ACCOUNT_JSON`, `FIREBASE_APP_ID`, `CREDENTIAL_FILE_CONTENT`.
`play_internal_testing_link` is optional (an org/repo variable, not a secret) -
omit the `with:` line entirely if you don't have one, rather than passing an empty
string.

**Versioning is git-native, not file-based.** The calling app's `app/build.gradle.kts`
must derive `versionCode`/`versionName` from git (see AgeCalculator's for the
pattern: `git rev-list --count HEAD` for versionCode, `git describe --tags` for
versionName, both overridable via `-PreleaseVersionCode`/`-PreleaseVersionName` -
this workflow passes those explicitly for the actual release build). There's no
`version.properties` to keep in sync across branches:

- **The next version** comes from the latest reachable `X.Y.Z` tag plus the release
  PR's declared bump type - see `## Version Bump` in `AgeCalculator`'s
  `.github/PULL_REQUEST_TEMPLATE/release.md` (`major`/`minor`/`patch`, default
  `patch`), extracted the same way `## Release Notes` is. With no tag yet at all,
  the first release is always `1.0.0`.
- **versionCode** is the calling repo's total commit count (`git rev-list --count`) -
  deterministic from any commit, so `promote-to-production` can reproduce the exact
  same number later purely from a tag, without needing to persist it anywhere.
- **The tag itself is only created after a confirmed successful Play Store upload** -
  a failed release leaves no trace (no orphaned tag or draft release) to clean up;
  the next attempt just recomputes the same next version fresh.

Requires `fetch-depth: 0` on checkout (already set in this workflow) - a shallow
checkout can't see the tags or full commit history this depends on.

### `cd-production-promote.yml` (reusable)

Manually promotes the build already sitting on Play Store's Internal Testing track
straight to Production - no rebuild, no re-upload, just a track move via the Play
Developer API. Deliberately `workflow_dispatch`-only in every caller: a production
rollout is irreversible-ish and user-facing, so it should always need an explicit
human trigger, never an automatic one.

Resolves the release tag to promote from `github.ref_name` when run from an explicit
tag ref (via the "Use workflow from" picker), or from the latest reachable `X.Y.Z`
tag when run from `main` directly - then recomputes that tag's versionCode via
`git rev-list --count <tag>`, reproducing exactly what was used when that release was
built. Also needs `fetch-depth: 0` (already set) for the same reason as above.

```yaml
name: CD - Promote to Production

on:
  workflow_dispatch:
    inputs:
      rollout_percentage:
        description: 'Percent of users to roll out to (1-100). Less than 100 starts a staged rollout.'
        required: true
        default: '100'

# No cancel-in-progress: a production rollout should never be killed mid-flight.
# This just queues a second trigger instead of letting two promotions race against
# the same track.
concurrency:
  group: promote-to-production-${{ github.ref }}

jobs:
  promote:
    # See the note under ci-build-test.yml's example above - required unless this
    # repo's default Actions token permissions are already "Read and write".
    permissions:
      contents: write
      pull-requests: write
    uses: aaatechnology/build-tools/.github/workflows/cd-production-promote.yml@main
    with:
      package_name: com.arun.agecalculator
      rollout_percentage: ${{ inputs.rollout_percentage }}
    secrets: inherit
```

Required secret on the calling repo: `PLAY_SERVICE_ACCOUNT_JSON`.

### A note on `secrets: inherit`

Every workflow above uses `secrets: inherit` to pass the calling repo's secrets
through automatically instead of listing each one out. It matches **by name**
(case-insensitively): a reusable workflow's `on.workflow_call.secrets` entry
`credential_file_content` is satisfied by a caller secret named
`CREDENTIAL_FILE_CONTENT`, `Credential_File_Content`, etc. - but nothing else. If a
caller doesn't have a secret with a matching name at all, the call fails with
`Secret <name> is required, but not provided while calling` (this bit us once
already - `firebase_credential_file_content` vs. the real `CREDENTIAL_FILE_CONTENT`
secret). When adding a new caller repo, double check its secret names against each
reusable workflow's `on.workflow_call.secrets` block above before assuming
`secrets: inherit` will just work.

## Gradle plugin: `aaatech.app-conventions`

A precompiled Gradle convention plugin, published from this repo (root
`build.gradle.kts`/`settings.gradle.kts`/`src/main/kotlin/`) to this repo's own
GitHub Packages Maven registry. It replaces the git-versioning, Jacoco, signing,
and common `android { }` scaffolding that used to be hand-rolled in every app
repo's own `app/build.gradle.kts` - see AgeCalculator's `app/build.gradle.kts` for
the reference consumer.

**What it does**, applied after `com.android.application` in an app's `plugins { }`:
- Git-native `versionCode`/`versionName` (`git rev-list --count HEAD` /
  `git describe --tags`), overridable for release builds via
  `-PreleaseVersionCode`/`-PreleaseVersionName` project properties - the same
  mechanism `cd-internal-testing.yml`/`cd-production-promote.yml` already rely on.
- Jacoco setup (`jacoco` plugin + `jacocoTestReportUnitOnly` task), reading Kotlin
  classes from AGP's built-in Kotlin compiler output path.
- Release signing: `signingConfigs["release"]` reads `../key/keystore.properties`
  (the default, `SigningKeyVersion.V2`) or `../key/keystore1.properties`
  (`SigningKeyVersion.V1`) - selected via `appConfig { signingKeyVersion = ... }`
  inside the consumer's `android { }` block. A no-op if the file is absent (e.g. a
  fresh checkout with no local signing key).
- Common `android { }` scaffolding: `compileOptions` (Java 17),
  `buildFeatures { buildConfig; resValues }`.
- `appName`/`enableLog`/`printLog` properties, settable directly inside each
  `buildTypes { debug { ... } }`/`release { ... }` block, generating the
  equivalent `BuildConfig.APP_NAME`/`ENABLE_LOG`/`PRINT_LOG` fields instead of
  manual `buildConfigField(...)` calls.
- **Locale filtering is opt-in, not automatic** - `appConfig { supportedLocales =
  listOf("en", "ta") }` (see below) sets `androidResources.localeFilters` to exactly
  that list, stripping every other locale's resources (including this app's own
  translations) during packaging. Leave it unset (the default) to ship every locale
  as-is, with no filtering at all. **List every locale this app's own resources use**
  - Astrology shipped with `values-ta/*` silently stripped from the packaged app for
    a while because an earlier version of this plugin defaulted to `localeFilters +=
    "en"` unconditionally, and Astrology's `values-ta` strings were never explicitly
    kept. Confirmed via `aapt2 dump resources`/`dump configurations`: the built APK
    had only the default (English) value for `birth_details`, no `ta`-qualified one,
    and no `ta` in the packaged configurations at all - the app couldn't have shown
    Tamil no matter what the UI code did. Only opt into this if you specifically want
    the smaller APK from dropping dependency-only locales (AndroidX, Play Services,
    etc. bundle translations for dozens of languages you likely never asked for).

**Consuming it in an app repo:**
1. Add the GitHub Packages repository to `settings.gradle.kts`'s
   `pluginManagement.repositories`:
   ```kotlin
   maven {
       name = "aaatechBuildTools"
       url = uri("https://maven.pkg.github.com/aaatechnology/build-tools")
       credentials {
           username = System.getenv("GITHUB_ACTOR")
           password = System.getenv("GH_PACKAGES_READ_TOKEN")
       }
   }
   ```
2. Apply it: `id("aaatech.app-conventions") version "<see build.gradle.kts's
   `version` in this repo for the current published version>"`. If the app supports
   more than one locale, also set (inside the consumer's own `android { }` block):
   ```kotlin
   appConfig {
       supportedLocales = listOf("en", "ta") // every locale this app's own resources use
   }
   ```
   Leave this unset for a single-locale (English) app - no filtering happens by
   default.
3. A `GH_PACKAGES_READ_TOKEN` repo secret (classic PAT, `read:packages` scope,
   created while logged in as the `aaatechnology` account - not a personal
   account, since the credential should be owned by the same account that owns
   this repo). GitHub Packages requires auth to resolve even though this repo is
   public - there's no anonymous pull like Docker Hub/npm. Publishing itself needs
   no extra secret - `GITHUB_TOKEN` already has write access to this repo's own
   packages.
4. Pass `gh_packages_read_token: ${{ secrets.gh_packages_read_token }}`-shaped
   access through to CI: `ci-build-test.yml` and `cd-internal-testing.yml` both
   declare this as a required secret already and set `GITHUB_ACTOR: aaatechnology`
   (hardcoded, not `github.actor` - it must match the token's actual owning
   account) as a job-level `env:` - `secrets: inherit` on the caller side is
   enough as long as the caller repo's secret is named `GH_PACKAGES_READ_TOKEN`.

**Publishing a new version:** bump `version` in this repo's root `build.gradle.kts`
and push to `main` - `.github/workflows/publish-plugin.yml` publishes automatically
on any push touching `build.gradle.kts`, `settings.gradle.kts`, `src/**`, or
`gradle/**` (or trigger it manually via `workflow_dispatch`). Versioning is manual,
not git-tag-driven like the app repos.

### `artifact-housekeeping.yml`

Runs daily, deletes Actions artifacts older than `max_age_days` (default 3) across
every repo listed in its `REPOS` env var, so artifact storage never re-accumulates
into another quota exhaustion. Requires a secret on **this** repo:

- `ARTIFACT_CLEANUP_TOKEN` - a personal access token (classic, `repo` scope, or
  fine-grained with Actions read/write) with access to every repo in `REPOS`. The
  default `GITHUB_TOKEN` only has access to this repo, not the others.

Add or remove repos by editing the `REPOS` list in the workflow file.
