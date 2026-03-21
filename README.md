# Digital Audio Workstation

[![CI](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/workflows/ci.yml/badge.svg)](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/workflows/ci.yml)

An open-source, state-of-the-art Digital Audio Workstation (DAW) built with **JavaFX** and **Java 25** for recording, mixing, and mastering audio.

## Project Structure

| Module | Description |
|--------|-------------|
| **daw-sdk** | Public SDK for plugin developers — interfaces, records, and enums that define the plugin API |
| **daw-core** | Core audio engine — audio processing, track management, mixer, and transport |
| **daw-app** | JavaFX desktop application — the main DAW user interface |

## Requirements

- **Java 25** (or newer)
- **Apache Maven 3.9+**

## Building

```bash
# Build all modules and run tests
mvn clean verify

# Build without tests
mvn clean package -DskipTests
```

## Running the Application

```bash
# Run via Maven
mvn javafx:run -pl daw-app

# Or run the built JAR
java -jar daw-app/target/daw-app-0.1.0-SNAPSHOT.jar
```

## Developing Plugins

The `daw-sdk` module provides the public API for creating plugins. Add it as a dependency in your plugin project:

```xml
<dependency>
    <groupId>com.benesquivelmusic</groupId>
    <artifactId>daw-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Implement the `DawPlugin` interface and register your plugin via `ServiceLoader`:

```java
public class MyReverbPlugin implements DawPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(
            "com.example.reverb",
            "My Reverb",
            "1.0.0",
            "Example Audio",
            PluginType.EFFECT
        );
    }

    @Override
    public void initialize(PluginContext context) { /* ... */ }

    @Override
    public void activate() { /* ... */ }

    @Override
    public void deactivate() { /* ... */ }

    @Override
    public void dispose() { /* ... */ }
}
```

Then create a `META-INF/services/com.benesquivelmusic.daw.sdk.plugin.DawPlugin` file listing your implementation class.

## Releasing

Releases are automated via GitHub Actions. Push a version tag to trigger a release build:

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
