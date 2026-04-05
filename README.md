# Digital Audio Workstation

[![CI](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/workflows/ci.yml/badge.svg)](https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation/actions/workflows/ci.yml)

An open-source, state-of-the-art Digital Audio Workstation (DAW) built with **JavaFX** and **Java 26** for recording, mixing, and mastering audio.

## Screenshots

**Session View** — multi-track arrangement with waveform editing, visualization tiles (oscilloscope, spectrum, level meters, LUFS loudness, stereo correlation), and real-time transport controls:

![DAW Main Session View](https://github.com/user-attachments/assets/1a26959c-9355-428f-ab96-ff5ac6e0742b)

**Mixer & Recording View** — full channel-strip mixer with per-channel faders, pan knobs, mute/solo/arm, live input monitoring, and master bus metering during an active recording session:

![DAW Mixer and Recording View](https://github.com/user-attachments/assets/19f13ddf-6567-40dd-8a1a-bd9714bf20e2)

**Sound Wave Telemetry View** — isometric 3D room visualizer showing sound sources, microphone placements, color-coded wave paths (cyan for direct, orange for reflected), animated energy particles, RT60 ambient glow, critical distance circles, a room statistics panel, and actionable placement suggestions. Room configurations are persisted with the project:

![DAW Sound Wave Telemetry View](https://github.com/user-attachments/assets/b0bc18a7-1d45-4f60-9df2-1f84eefe2dee)

## Project Structure

| Module | Description |
|--------|-------------|
| **daw-sdk** | Public SDK for plugin developers — interfaces, records, and enums that define the plugin API |
| **daw-core** | Core audio engine — audio processing, track management, mixer, and transport |
| **daw-acoustics** | Pure-Java room acoustics engine — image-source early reflections and FDN late reverberation |
| **daw-app** | JavaFX desktop application — the main DAW user interface |

## Requirements

- **Java 26** (or newer)
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

## Contributing

Contributions are welcome! Here's how to get started:

1. **Fork** this repository and clone your fork locally.
2. **Create a branch** for your change: `git checkout -b feature/my-feature`.
3. **Make your changes**, following the existing code style and conventions.
4. **Run the tests** to make sure everything passes: `mvn clean verify`.
5. **Commit** your changes with a clear, descriptive message.
6. **Push** your branch and open a **Pull Request** against the `main` branch.

### Guidelines

- Keep pull requests focused — one feature or fix per PR.
- Write or update tests for any code you change.
- Update documentation (including this README) if your change affects behavior visible to users or plugin developers.
- Be respectful and constructive in reviews and discussions. This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

For larger changes, please open an issue first to discuss your proposal before investing time in implementation.

## Releasing

Releases are automated via GitHub Actions. Push a version tag to trigger a release build:

```bash
git tag v0.1.0
git push origin v0.1.0
```

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
