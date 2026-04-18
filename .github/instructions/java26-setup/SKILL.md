---
name: java26-setup
description: Install the latest Adoptium Temurin JDK 26 build from GitHub releases. Use when the environment lacks Java 26, when `java -version` reports an older JDK, or when Maven compilation fails because release 26 is not supported.
argument-hint: (no arguments required)
allowed-tools: Bash
---

# Java 26 Setup: Install Adoptium Temurin JDK 26

Install the latest Adoptium Temurin JDK 26 early-access build so Maven can compile and test this project.

## When to Use

- `java -version` reports a JDK older than 26
- `mvn compile` fails with `error: release version 26 not supported`
- The environment does not have JDK 26 installed

## Release Source

All binaries come from **https://github.com/adoptium/temurin26-binaries/releases**.

## Asset Naming Convention

Adoptium tags follow `jdk-MAJOR+BUILD` (example: `jdk-26+35`).
The Linux HotSpot tarball naming format is:

```bash
OpenJDK26U-jdk_<ARCH>_linux_hotspot_<MAJOR>_<BUILD>.tar.gz
```

`ARCH` values:
- `x64` (for `x86_64`)
- `aarch64`

## Installation Steps

### 1) Detect architecture and find latest JDK 26 tag

> Run steps **2-5 in the same shell session** as step 1 so strict mode and exported variables remain active.
> Copy and run the code block below in its entirety.

```bash
set -euo pipefail  # strict mode: fail on errors, unset variables, and propagate failures from pipelines

ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  ADOPT_ARCH="x64" ;;
  aarch64) ADOPT_ARCH="aarch64" ;;
  *)       echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

# Resolve latest release via redirect target: .../releases/tag/jdk-26+<build>
LATEST_URL=$(curl -fsSL -o /dev/null -w '%{url_effective}' \
  https://github.com/adoptium/temurin26-binaries/releases/latest)
LATEST_TAG=$(printf '%s' "$LATEST_URL" | sed -En 's#.*/tag/(jdk-26\+[0-9]+)$#\1#p')

if [ -z "$LATEST_TAG" ]; then
  echo "ERROR: Could not determine latest Temurin 26 release tag"; exit 1
fi

MAJOR=26
BUILD=${LATEST_TAG#jdk-26+}
FILENAME="OpenJDK26U-jdk_${ADOPT_ARCH}_linux_hotspot_${MAJOR}_${BUILD}.tar.gz"
SHA_FILE="${FILENAME}.sha256.txt"
BASE_URL="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-${MAJOR}%2B${BUILD}"
TARBALL_URL="${BASE_URL}/${FILENAME}"
SHA_URL="${BASE_URL}/${SHA_FILE}"

echo "Resolved release tag: ${LATEST_TAG}"
echo "Resolved asset: ${FILENAME}"
```

### 2) Download tarball and verify checksum

```bash
curl -fsSL -o "/tmp/${FILENAME}" "$TARBALL_URL"
curl -fsSL -o "/tmp/${SHA_FILE}" "$SHA_URL"

(
  # The checksum file references the tarball by filename only, so run verification from /tmp.
  cd /tmp
  sha256sum -c "$SHA_FILE"
)
```

### 3) Extract and set `JAVA_HOME`

Choose one install mode:

#### A) System-wide install (requires sudo)

```bash
sudo mkdir -p /usr/lib/jvm
sudo tar xzf "/tmp/${FILENAME}" -C /usr/lib/jvm
JAVA_HOME="/usr/lib/jvm/${LATEST_TAG}"
```

#### B) User-local install (no sudo)

```bash
mkdir -p "$HOME/.local/jdks"
tar xzf "/tmp/${FILENAME}" -C "$HOME/.local/jdks"
JAVA_HOME="$HOME/.local/jdks/${LATEST_TAG}"
```

Activate Java 26 in the current shell:

```bash
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
```

### 4) Verify

```bash
java -version
mvn -version
# Maven output should show Java version: 26
```

### 5) Fix missing CA certificates (if Maven downloads fail with PKIX errors)

Early-access Temurin 26 builds may ship with an **empty or incomplete `cacerts` truststore**, causing Maven dependency downloads from Maven Central (or any HTTPS repo) to fail with:

```
PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target
```

The fix is to replace the JDK 26 truststore with one from a pre-installed system JDK (Temurin 21 or 25 are typical). Do NOT configure Maven to disable TLS verification — that's insecure.

```bash
# Find a good source truststore (prefer an already-installed system JDK).
for candidate in \
    /usr/lib/jvm/temurin-25-jdk-amd64/lib/security/cacerts \
    /usr/lib/jvm/temurin-21-jdk-amd64/lib/security/cacerts \
    /etc/ssl/certs/java/cacerts; do
  if [ -f "$candidate" ]; then
    SYSTEM_CACERTS="$candidate"
    break
  fi
done

if [ -z "${SYSTEM_CACERTS:-}" ]; then
  echo "ERROR: No system cacerts found. Install ca-certificates-java or a system JDK first."
  exit 1
fi

# Replace the JDK 26 truststore. Back up first for safety.
cp "${JAVA_HOME}/lib/security/cacerts" "${JAVA_HOME}/lib/security/cacerts.bak"
cp "$SYSTEM_CACERTS" "${JAVA_HOME}/lib/security/cacerts"

# Purge any poisoned .lastUpdated markers so Maven retries the failed downloads.
find "$HOME/.m2/repository" -name '*.lastUpdated' -delete 2>/dev/null || true

echo "Truststore replaced from: $SYSTEM_CACERTS"
```

After this, `mvn compile test` should download artifacts successfully.

### 6) Cleanup downloaded files

```bash
rm -f "/tmp/${FILENAME}" "/tmp/${SHA_FILE}"
```

## Quick One-Liner (user-local install, no sudo)

> Keep this one-liner aligned with the step-by-step commands above when updating logic.

```bash
set -euo pipefail && \
ARCH=$(uname -m) && case "$ARCH" in x86_64) ADOPT_ARCH="x64";; aarch64) ADOPT_ARCH="aarch64";; *) echo "Unsupported: $ARCH"; exit 1;; esac && \
LATEST_URL=$(curl -fsSL -o /dev/null -w '%{url_effective}' https://github.com/adoptium/temurin26-binaries/releases/latest) && \
TAG=$(printf '%s' "$LATEST_URL" | sed -En 's#.*/tag/(jdk-26\+[0-9]+)$#\1#p') && [ -n "$TAG" ] || { echo "ERROR: Could not determine latest Temurin 26 release tag"; exit 1; } && \
MAJOR=26 && BUILD=${TAG#jdk-26+} && FILE="OpenJDK26U-jdk_${ADOPT_ARCH}_linux_hotspot_${MAJOR}_${BUILD}.tar.gz" && SHA="${FILE}.sha256.txt" && \
BASE="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-${MAJOR}%2B${BUILD}" && \
curl -fsSL -o "/tmp/${FILE}" "${BASE}/${FILE}" && curl -fsSL -o "/tmp/${SHA}" "${BASE}/${SHA}" && \
(cd /tmp && sha256sum -c "$SHA") && \
mkdir -p "$HOME/.local/jdks" && tar xzf "/tmp/${FILE}" -C "$HOME/.local/jdks" && \
export JAVA_HOME="$HOME/.local/jdks/${TAG}" && export PATH="${JAVA_HOME}/bin:${PATH}" && \
java -version && mvn -version
```

## Notes

- Temurin 26 builds are early-access and updated frequently.
- This skill resolves the latest release dynamically from GitHub redirects.
- Use `curl -f` so failed downloads stop immediately instead of producing silent partial setup.
- Early-access JDK 26 truststores can be incomplete and break Maven HTTPS downloads with `PKIX path building failed` errors — always perform step 5 in a fresh environment. See step 5 for the secure fix (copy `cacerts` from a pre-installed system JDK; never disable TLS verification).
- For GitHub Actions CI, prefer `actions/setup-java@v4` with `distribution: temurin` and `java-version: '26'`.
