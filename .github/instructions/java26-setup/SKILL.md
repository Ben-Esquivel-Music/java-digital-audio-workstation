---
name: java26-setup
description: Install the latest Adoptium Temurin JDK 26 build from GitHub releases. Use when the environment lacks Java 26, when `java -version` reports an older JDK, or when Maven compilation fails because release 26 is not supported.
argument-hint: (no arguments required)
allowed-tools: Bash
---

# Java 26 Setup: Install Adoptium Temurin JDK 26

Install the latest Adoptium Temurin JDK 26 early-access build so that Maven can compile and test this project.

## When to Use

- `java -version` reports a JDK older than 26
- `mvn compile` fails with `error: release version 26 not supported`
- The environment does not have JDK 26 installed

## Release Source

All binaries come from **https://github.com/adoptium/temurin26-binaries/releases**.

## Asset Naming Convention

Adoptium tags follow the pattern `jdk-MAJOR+BUILD` (e.g. `jdk-26+35`).
Binary file names follow:

```
OpenJDK26U-jdk_<ARCH>_linux_hotspot_<MAJOR>_<BUILD>.tar.gz
```

| Placeholder | Values |
|-------------|--------|
| `ARCH` | `x64`, `aarch64` |
| `MAJOR` | `26` |
| `BUILD` | Build number from the tag (e.g. `35`) |

Examples:
- `OpenJDK26U-jdk_x64_linux_hotspot_26_35.tar.gz`
- `OpenJDK26U-jdk_aarch64_linux_hotspot_26_35.tar.gz`

## Installation Steps

Run the following bash commands to download, extract, and activate Temurin JDK 26.

### 1. Detect architecture and discover the latest release tag

```bash
# Detect architecture
ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  ADOPT_ARCH="x64" ;;
  aarch64) ADOPT_ARCH="aarch64" ;;
  *)       echo "Unsupported architecture: $ARCH" && exit 1 ;;
esac

# Scrape the latest release tag from the releases page
LATEST_TAG=$(curl -sL https://github.com/adoptium/temurin26-binaries/releases \
  | grep -oP 'jdk-26\+\d+' | head -1)

if [ -z "$LATEST_TAG" ]; then
  echo "ERROR: Could not determine latest Temurin 26 release tag" && exit 1
fi

echo "Latest tag: $LATEST_TAG"

# Parse major and build number from the tag (e.g. jdk-26+35 → MAJOR=26, BUILD=35)
MAJOR=$(echo "$LATEST_TAG" | sed 's/jdk-\([0-9]*\)+.*/\1/')
BUILD=$(echo "$LATEST_TAG" | sed 's/jdk-[0-9]*+//')
```

### 2. Download and extract

```bash
FILENAME="OpenJDK26U-jdk_${ADOPT_ARCH}_linux_hotspot_${MAJOR}_${BUILD}.tar.gz"
URL="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-${MAJOR}%2B${BUILD}/${FILENAME}"

echo "Downloading $URL"
curl -sL -o "/tmp/${FILENAME}" "$URL"

sudo mkdir -p /usr/lib/jvm
sudo tar xzf "/tmp/${FILENAME}" -C /usr/lib/jvm
rm "/tmp/${FILENAME}"
```

### 3. Set JAVA_HOME and update PATH

```bash
# The extracted directory is named like jdk-26+35
JAVA_HOME="/usr/lib/jvm/${LATEST_TAG}"
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "JAVA_HOME=${JAVA_HOME}"
java -version
```

### 4. Verify

```bash
java -version
# Expected output: openjdk version "26" ...
mvn -version
# Should show Java version: 26 ...
```

## Complete One-Liner

For quick setup, copy and run this single block:

```bash
ARCH=$(uname -m) && \
case "$ARCH" in x86_64) ADOPT_ARCH="x64";; aarch64) ADOPT_ARCH="aarch64";; *) echo "Unsupported: $ARCH" && exit 1;; esac && \
TAG=$(curl -sL https://github.com/adoptium/temurin26-binaries/releases | grep -oP 'jdk-26\+\d+' | head -1) && \
MAJOR=$(echo "$TAG" | sed 's/jdk-\([0-9]*\)+.*/\1/') && \
BUILD=$(echo "$TAG" | sed 's/jdk-[0-9]*+//') && \
FILE="OpenJDK26U-jdk_${ADOPT_ARCH}_linux_hotspot_${MAJOR}_${BUILD}.tar.gz" && \
curl -sL -o "/tmp/${FILE}" "https://github.com/adoptium/temurin26-binaries/releases/download/jdk-${MAJOR}%2B${BUILD}/${FILE}" && \
sudo mkdir -p /usr/lib/jvm && \
sudo tar xzf "/tmp/${FILE}" -C /usr/lib/jvm && \
rm "/tmp/${FILE}" && \
export JAVA_HOME="/usr/lib/jvm/${TAG}" && \
export PATH="${JAVA_HOME}/bin:${PATH}" && \
java -version
```

## Notes

- These are **early-access builds**; the tag and build number change with each new release.
- The skill always scrapes the latest tag so it stays current without hard-coded versions.
- After setting `JAVA_HOME`, Maven (`mvn`) automatically uses the new JDK.
- For CI workflows, prefer `actions/setup-java@v4` with `distribution: 'temurin'` and `java-version: '26'` instead.
