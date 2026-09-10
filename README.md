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

Builds a signed release App Bundle + APK, creates a draft GitHub release, uploads
the AAB to Play Store Internal Testing, uploads the release APK to Firebase App
Distribution, and bumps the version on `develop` after a confirmed successful Play
Store upload. Call it on push to `main`:

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

**Choosing a major/minor/patch bump without a manual trigger**: the release PR
(develop → main) declares it directly in its body, under a `## Version Bump`
section - `major`, `minor`, or `patch` (default, used for anything blank/
unrecognized too). This workflow extracts that section the same way it extracts
`## Release Notes`, and passes the corresponding `-PmajorVersion`/`-PminorVersion`
flag to the release build - the calling repo's `AppProperty`-based versioning
already understands these flags (they previously only ever got passed by running
Gradle locally by hand). Patch needs no flag: it relies on the "Bump version on
develop" step (below) having already advanced develop one patch past the last
release, so the next release-tagged PR merge just ships that value as-is. See
`AgeCalculator`'s `.github/PULL_REQUEST_TEMPLATE/release.md` for the exact section
format expected.

### `android-promote-production.yml` (reusable)

Manually promotes the build already sitting on Play Store's Internal Testing track
straight to Production - no rebuild, no re-upload, just a track move via the Play
Developer API. Deliberately `workflow_dispatch`-only in every caller: a production
rollout is irreversible-ish and user-facing, so it should always need an explicit
human trigger, never an automatic one.

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
