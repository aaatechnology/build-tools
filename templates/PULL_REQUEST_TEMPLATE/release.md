## Summary

<!-- Internal/engineering context: what changed and why. Not shown to end users. -->

## Test plan

<!-- How did you verify this works? Commands run, screenshots, manual steps. -->

<!-- Required before every release PR: the instrumented (UI) tests on a connected device, every flavour,
from `develop` as merged. CI doesn't run them (no emulator in Actions), so this is the only run.
  ANDROID_SERIAL=<device> ./gradlew :app:connected<Flavor>DebugAndroidTest ... --continue
(one task per flavour: printAppInfo's <FLAVOR>_DEBUG_UI_TEST_TASK; `:app:connectedDebugAndroidTest` without
flavours). Results: app/build/outputs/androidTest-results/connected/debug/. Any failure stops the release PR. -->

- [ ] UI tests on device (`develop` at `<commit>`, `<device>`, Android `<version>`): `<flavour>` `<passed>/<total>`, ...

## Version Bump

<!--
major, minor, or patch. This exact section is extracted automatically and used to
compute the next release version from the latest git tag - major/minor cause a real
version bump, patch (the default, used if this section is left as "patch", blank, or
unrecognized) just advances the patch number by one.
  - major: breaking changes / a big milestone (resets minor and patch to 0)
  - minor: new features, backward compatible (resets patch to 0)
  - patch: bug fixes, small changes
-->

patch

## Release Notes

<!--
User-facing summary of what's new in this release. This exact section (and only this
section) is extracted automatically and used as-is for:
  - the GitHub draft release created after this PR merges
  - the Play Store "what's new" text (Internal Testing track)
Keep it user-facing, not internal/engineering detail, and concise - Play Store caps
"what's new" at 500 characters per locale, longer text gets truncated.
Remove this comment and the placeholder line below before submitting.
-->

-
