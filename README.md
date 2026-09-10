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
    secrets: inherit
```

`secrets: inherit` passes the calling repo's `FIREBASE_APP_ID` and
`CREDENTIAL_FILE_CONTENT` secrets through automatically (the reusable workflow reads
them as `firebase_app_id` / `firebase_credential_file_content` - see its
`on.workflow_call.secrets` block). See the file itself for all available inputs.

### `artifact-housekeeping.yml`

Runs daily, deletes Actions artifacts older than `max_age_days` (default 3) across
every repo listed in its `REPOS` env var, so artifact storage never re-accumulates
into another quota exhaustion. Requires a secret on **this** repo:

- `ARTIFACT_CLEANUP_TOKEN` - a personal access token (classic, `repo` scope, or
  fine-grained with Actions read/write) with access to every repo in `REPOS`. The
  default `GITHUB_TOKEN` only has access to this repo, not the others.

Add or remove repos by editing the `REPOS` list in the workflow file.
