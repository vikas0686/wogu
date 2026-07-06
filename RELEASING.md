# Releasing

WoGu's modules are versioned in lockstep (see [VERSIONING.md](VERSIONING.md)).
[`.github/workflows/release.yml`](.github/workflows/release.yml) publishes a release
whenever a `release-X.Y.Z` tag is pushed: it builds and tests the reactor, publishes the Maven
artifacts to Maven Central, publishes `wogu-gradle-plugin` to the Gradle Plugin Portal,
and creates a GitHub Release with the built jars attached.

## One-time setup: GitHub Actions secrets

The workflow needs these repository secrets (**Settings > Secrets and variables >
Actions > New repository secret**, or attach them to a `release` environment instead —
see below):

| Secret             | Where it comes from                                                                                                                     |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `GPG_PRIVATE_KEY`   | `gpg --export-secret-keys --armor <KEY_ID>` — paste the full `-----BEGIN PGP PRIVATE KEY BLOCK-----` output.                             |
| `GPG_PASSPHRASE`    | The passphrase for that key.                                                                                                            |
| `CENTRAL_USERNAME`  | Sonatype Central Portal User Token username, from https://central.sonatype.com/account (Generate User Token).                          |
| `CENTRAL_PASSWORD`  | The matching User Token password from the same page.                                                                                    |
| `GRADLE_PUBLISH_KEY`| Gradle Plugin Portal API key, from https://plugins.gradle.org/u/<you>/publish (API Keys).                                               |
| `GRADLE_PUBLISH_SECRET` | The matching API secret from the same page.                                                                                         |

You said you already hold the GPG key and other credentials locally — `GPG_PRIVATE_KEY`
is the one that needs exporting (`gpg --export-secret-keys --armor`); the rest are
generated directly on the Sonatype/Gradle sites above, not derived from anything local.

### Optional but recommended: a manual approval gate

`publish-maven-central` and `publish-gradle-plugin` both run under a `release`
[GitHub Environment](https://docs.github.com/en/actions/deployment/targeting-different-environments/using-environments-for-deployment).
Create one at **Settings > Environments > New environment**, name it `release`, and add
yourself as a required reviewer. That way, pushing a tag still triggers the workflow
immediately, but the two irreversible publish steps (Maven Central and the Gradle Plugin
Portal don't let you unpublish a version) pause for your explicit approval before running.
If you skip this, the workflow publishes automatically the moment the tag is pushed.

Repository secrets are visible to every environment by default; you don't have to
duplicate them into the `release` environment specifically unless you want to restrict
them further.

## Cutting a release

1. Bump the `<revision>` property in the root `pom.xml` — the one place the version lives.
   Every module's `pom.xml` (via `${revision}` in its `<parent>`) and
   `wogu-gradle-plugin/build.gradle.kts` (which reads `<revision>` straight out of the
   root `pom.xml`) both derive from it automatically; nothing else needs editing.
2. Move the `[Unreleased]` section of [CHANGELOG.md](CHANGELOG.md) under a new
   `## [X.Y.Z] - YYYY-MM-DD` heading.
3. Commit ("Prepare vX.Y.Z release"), push, and let `ci.yml` go green on `main`.
4. Tag and push: `git tag release-X.Y.Z && git push origin release-X.Y.Z`.
5. Watch the `Release` workflow run in the Actions tab. If you configured the `release`
   environment gate, approve the pending deployment when prompted.
6. The Maven Central upload lands as a *pending* deployment (`autoPublish=false` in the
   root `pom.xml`'s `release` profile) — review it at
   https://central.sonatype.com/publishing/deployments and click publish there manually.
7. Confirm the new version shows up on the Gradle Plugin Portal and that the GitHub
   Release was created with the jars attached.

## Releasing locally instead of via CI

`mvn clean deploy -Prelease` works from a laptop too, given a `~/.m2/settings.xml` with:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_USER_TOKEN_USERNAME</username>
      <password>YOUR_CENTRAL_USER_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

With no `MAVEN_GPG_PASSPHRASE` env var set, `maven-gpg-plugin` falls back to an
interactive terminal prompt (loopback pinentry, configured in the root `pom.xml`), cached
by `gpg-agent` for the rest of the session — nothing secret needs to live in this file.

## Testing the pipeline without publishing anything real

`publish-maven-central` and `publish-gradle-plugin` both fail fast if their secrets are
missing, so you can push a tag to exercise the `verify` job (build, test, the
sample-project check, the Gradle plugin build) before any publishing secrets exist — it's
the two publish jobs and the final GitHub Release that need them.
