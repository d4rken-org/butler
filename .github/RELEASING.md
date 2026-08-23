# Releasing Butler

## How to cut a release

1. Go to **Actions → Release prepare → Run workflow**.
2. Pick:
   - **bump_kind**: `build` (follow-up build), `patch` (bug fix), `minor` (new feature), `major` (breaking change). Ignored when `version_override` is set.
   - **version_type**: `keep-current` (usual), or `rc` / `beta` to switch channel.
   - **version_override**: an explicit version such as `0.2.0-beta0`, overriding the two above.
   - **expected_current**: optional safety check, fails if `main` is not on the version you expect.
   - **dry_run**: `true` to preview the plan, `false` to commit, tag and push.
3. With `dry_run=false`, job 1 validates and job 2 commits the bump, creates an annotated tag, and pushes both atomically. That push is made with a GitHub App token, which is what makes `release-tag.yml` fire; a `GITHUB_TOKEN` push would not trigger it.
4. Approve or reject the Play upload when it asks. See "Play uploads are gated" below.

A dry run does not exercise the App token: token minting lives in job 2, which only runs when `dry_run` is false.

## Version scheme

Defined in `tools/release/bump.sh`. All fields must be in `0..99`, the formula overflows at 100:

```
versionName = <major>.<minor>.<patch>-<type><build>   (e.g. 0.1.0-beta1)
versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + build*10
```

Files updated by every release:
- `version.properties`, read by Gradle at build time.
- `VERSION`, plain text for third-party consumers such as F-Droid.

## Tag to channel mapping

| Tag suffix | FOSS build | GitHub release | Gplay lane | Play track | Rollout |
|---|---|---|---|---|---|
| `-beta<n>` | `assembleFossBeta` | pre-release | `lane :beta` | `alpha` | full |
| `-rc<n>` | `assembleFossRelease` | release | `lane :production` | `alpha` | full |

Butler is in closed testing, so `lane :production` uploads to the **`alpha`** track, not to production. Play's API track names do not match the Console labels: `alpha` is Closed testing, `beta` is Open testing. Promotion out of closed testing happens in the Console. When Butler leaves closed testing, move both lanes up together in `fastlane/Fastfile` rather than letting them drift onto different tracks.

`lane :listing_only` and `lane :screenshots_only` name the `production` track but upload no binaries; they only refresh store listing metadata and screenshots.

## Play uploads are gated

The `gplay-production` environment requires a review from @d4rken, so every Play upload pauses until it is approved or rejected from the run page. Rejecting marks that job red and leaves the rest of the release untouched; the GitHub release job runs in parallel and does not depend on it.

This is deliberate. A rejected deployment never executes a step, so nothing reaches Play. There is no way to cancel a single job otherwise, cancelling the run would kill the FOSS build with it.

## Validation guards

- `check-release-tooling` in `code-checks.yml` runs shellcheck, the bats suite, and a live `bump.sh --mode=check` on every PR, so `version.properties` and `VERSION` cannot drift apart unnoticed.
- `validate-tag` in `release-tag.yml` runs `bump.sh --mode=check --expected-tag=<tag>` on every tag push and rejects a tag that does not match the committed version.
- The pre-flight in `release-prepare.yml` fails closed. It reads the required check contexts from the branch ruleset and refuses to release if that read fails, if no contexts are configured, if a required check ended in anything other than success, neutral or skipped, or if checks have not settled after polling for roughly ten minutes.
- Job 2 re-checks the plan and pins the commit: it checks out the exact SHA job 1 validated and aborts if `origin/main` has moved off it, so a merge landing mid-run cannot be released unvalidated.
- A non-tag ref cannot publish. `release-tag.yml` refuses a live run whose ref is not a `v*` tag, which is what stops a `workflow_dispatch` from `main` building the production variant.

## Reruns after a failure

Releases are created as drafts, get the APK attached, then published, because a published release can be read-only depending on repository settings. On a rerun, `release-tag.yml` reconciles what already exists: a complete release is skipped so the rerun is green, a stale draft from a failed run is deleted and rebuilt, and a published release with no APK fails loudly because it cannot be repaired. In that last case, cut a new tag.

## Emergency release

The rulesets grant bypass to the `d4rken-org-releaser` App only, so a human direct push to `main` or a hand-pushed `v*` tag is rejected. If Actions is unavailable:

1. An org admin disables the relevant ruleset, or adds the human account as a bypass actor.
2. Run `bash tools/release/bump.sh --mode=write --bump-kind=<kind>` locally, then commit, tag and push.
3. Restore the ruleset immediately afterwards.

## Repo setup

Already configured, listed so it can be checked if a release ever fails at the push step:

1. `d4rken-org-releaser` is installed org-wide with `contents: write`.
2. Org secrets `RELEASE_APP_CLIENT_ID` and `RELEASE_APP_PRIVATE_KEY` are visible to this repo, as is `GPLAY_SERVICE_ACCOUNT_KEY_JSON_BASE64`.
3. The App is a bypass actor on both the `main` branch ruleset and the `v*` tag ruleset.
4. Signing material lives in the `foss-production` and `gplay-production` environment secrets.
