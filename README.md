# build-tools

Shared CI workflows (and eventually common Gradle build logic) for aaatechnology
Android app repos. Extracted after the same GitHub Actions storage-quota bug was
found independently copy-pasted across six app repos - fixing it once here means it
can't silently drift out of sync per-repo again.

## Workflows

### `android-debug-ci.yml` (reusable)

Builds the debug APK, runs unit tests + Jacoco coverage, and distributes the APK via
Firebase App Distribution. Call it from an app repo's own workflow like:

```yaml
name: CI-Android APK

on:
  pull_request:
    branches: [ develop, main ]
    paths-ignore:
      - '**.md'
  workflow_dispatch:

concurrency:
  group: ci-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    uses: aaatechnology/build-tools/.github/workflows/android-debug-ci.yml@main
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

### `android-release-play-store.yml` (reusable)

Builds a signed release App Bundle + APK, uploads the AAB to Play Store Internal
Testing, uploads the release APK to Firebase App Distribution, and - only once that
upload is confirmed successful - creates the git tag + draft GitHub release that
records the shipped version. Call it on push to `main`:

```yaml
name: Release to Play Store (Internal Testing)

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
    uses: aaatechnology/build-tools/.github/workflows/android-release-play-store.yml@main
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

- **The next version** comes from the latest reachable `vX.Y.Z` tag plus the release
  PR's declared bump type - see `## Version Bump` in `AgeCalculator`'s
  `.github/PULL_REQUEST_TEMPLATE/release.md` (`major`/`minor`/`patch`, default
  `patch`), extracted the same way `## Release Notes` is. With no tag yet at all,
  the first release is always `v1.0.0`.
- **versionCode** is the calling repo's total commit count (`git rev-list --count`) -
  deterministic from any commit, so `promote-to-production` can reproduce the exact
  same number later purely from a tag, without needing to persist it anywhere.
- **The tag itself is only created after a confirmed successful Play Store upload** -
  a failed release leaves no trace (no orphaned tag or draft release) to clean up;
  the next attempt just recomputes the same next version fresh.

Requires `fetch-depth: 0` on checkout (already set in this workflow) - a shallow
checkout can't see the tags or full commit history this depends on.

### `android-promote-production.yml` (reusable)

Manually promotes the build already sitting on Play Store's Internal Testing track
straight to Production - no rebuild, no re-upload, just a track move via the Play
Developer API. Deliberately `workflow_dispatch`-only in every caller: a production
rollout is irreversible-ish and user-facing, so it should always need an explicit
human trigger, never an automatic one.

Resolves the release tag to promote from `github.ref_name` when run from an explicit
tag ref (via the "Use workflow from" picker), or from the latest reachable `vX.Y.Z`
tag when run from `main` directly - then recomputes that tag's versionCode via
`git rev-list --count <tag>`, reproducing exactly what was used when that release was
built. Also needs `fetch-depth: 0` (already set) for the same reason as above.

```yaml
name: Promote Internal Testing to Production

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
    uses: aaatechnology/build-tools/.github/workflows/android-promote-production.yml@main
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

### `artifact-housekeeping.yml`

Runs daily, deletes Actions artifacts older than `max_age_days` (default 3) across
every repo listed in its `REPOS` env var, so artifact storage never re-accumulates
into another quota exhaustion. Requires a secret on **this** repo:

- `ARTIFACT_CLEANUP_TOKEN` - a personal access token (classic, `repo` scope, or
  fine-grained with Actions read/write) with access to every repo in `REPOS`. The
  default `GITHUB_TOKEN` only has access to this repo, not the others.

Add or remove repos by editing the `REPOS` list in the workflow file.
