# Publishing to Maven Central

This document describes how to publish KotlinLLM to Maven Central.

## Prerequisites

### 1. Sonatype OSSRH Account

1. Create an account at [Sonatype JIRA](https://issues.sonatype.org/secure/Signup!default.jspa)
2. Create a new project ticket requesting access to `com.kotlinllm` group ID
3. Wait for approval (usually 1-2 business days)

### 2. GPG Key

Generate a GPG key for signing artifacts:

```bash
# Generate key
gpg --gen-key

# List keys to get key ID
gpg --list-secret-keys --keyid-format LONG

# Export public key to key servers
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID

# Export private key (base64 encoded for GitHub Secrets)
gpg --armor --export-secret-keys YOUR_KEY_ID | base64
```

## GitHub Actions Setup

The release workflow (`.github/workflows/release.yml`) automatically publishes to Maven Central when a version tag is pushed.

### Required Secrets

Configure these secrets in your GitHub repository settings:

| Secret | Description |
|--------|-------------|
| `MAVEN_USERNAME` | Sonatype OSSRH username |
| `MAVEN_PASSWORD` | Sonatype OSSRH password or token |
| `SIGNING_KEY` | Base64-encoded GPG private key |
| `SIGNING_PASSWORD` | GPG key passphrase |

### Setting Up Secrets

1. Go to repository **Settings** > **Secrets and variables** > **Actions**
2. Click **New repository secret** for each secret above

## Release Process

### 1. Update Version

Edit `build.gradle.kts`:

```kotlin
version = "1.0.0"
```

### 2. Update Changelog

Add release notes to `CHANGELOG.md`.

### 3. Commit and Tag

```bash
git add .
git commit -m "Release v1.0.0"
git tag v1.0.0
git push origin main
git push origin v1.0.0
```

### 4. Monitor Release

1. Check **Actions** tab for workflow progress
2. Verify artifacts at [Sonatype Nexus](https://s01.oss.sonatype.org/)
3. Artifacts sync to Maven Central within 2-4 hours

## Local Publishing

### Publish to Local Maven Repository

```bash
./gradlew publishToMavenLocal
```

Artifacts are published to `~/.m2/repository/com/kotlinllm/`.

### Dry Run (without signing)

```bash
./gradlew publish --dry-run
```

## Manual Publishing

If needed, publish manually:

```bash
export MAVEN_USERNAME=your-username
export MAVEN_PASSWORD=your-password
export SIGNING_KEY=$(cat your-key.asc | base64)
export SIGNING_PASSWORD=your-passphrase

./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

## Troubleshooting

### Signing Fails

- Ensure `SIGNING_KEY` is base64-encoded
- Verify GPG key is not expired
- Check passphrase is correct

### Upload Fails

- Verify Sonatype credentials
- Check group ID is approved
- Ensure POM has required fields (name, description, url, license, developers, scm)

### Artifacts Not on Maven Central

- Check [Sonatype Nexus](https://s01.oss.sonatype.org/) staging repositories
- Manually close and release if needed
- Sync to Maven Central takes 2-4 hours

## Module Artifacts

Published artifacts:

| Artifact | Description |
|----------|-------------|
| `com.kotlinllm:kotlinllm-core` | Core functionality, providers, memory, structured output |
| `com.kotlinllm:kotlinllm-resilience` | Rate limiting, circuit breaker |
| `com.kotlinllm:kotlinllm-observability` | Metrics, logging |
| `com.kotlinllm:kotlinllm-documents` | Document loaders, chunking |
| `com.kotlinllm:kotlinllm-jpa` | JPA persistence adapter |
| `com.kotlinllm:kotlinllm-exposed` | Exposed persistence adapter |
