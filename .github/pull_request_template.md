## Summary

<!-- What this PR changes and why, in 1-3 sentences. -->



## Changes

<!-- Each change in detail, grouped by area. Delete the areas this PR doesn't touch. -->

### Gradle plugin (`src/main/kotlin`)

-

### Reusable workflows (`.github/workflows`)

-

### Docs

-

## Impact on app repos

<!-- New or changed inputs, tasks or behaviour, and what a caller has to change to adopt it
(a new input to pass, a new tag to pin, a Gradle flag to set). "None" if nothing changes for them. -->

-

## Version Bump

<!-- Exactly one of:
  patch  - fixes, no new inputs or tasks
  minor  - new inputs, tasks or behaviour that existing callers don't have to adopt
  major  - breaking: callers must change something to keep working
  none   - no release (docs, this repo's own CI)
On merge, the release workflow sets `version` in build.gradle.kts from the latest tag and this
bump, publishes the plugin and tags the release. Don't edit `version` in this PR. -->

patch

## Test plan

<!-- How this was checked. For plugin or workflow changes, say which app it was tried against. -->

- [ ] PR checks pass (actionlint, plugin build, this template)
- [ ] Tried against an app: `./gradlew publishToMavenLocal` here, then `mavenLocal()` and the new version in the app
-

## Release Notes

<!-- The GitHub release text for this version: what changed for app repos, in a few bullets.
Not needed with "none". Keep this section last. -->

-
