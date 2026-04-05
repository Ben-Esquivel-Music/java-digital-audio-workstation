package com.benesquivelmusic.daw.core.audioimport;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Shared decoder utility that uses {@link javax.sound.sampled.AudioSystem}
 * to decode audio files into raw PCM, then converts to normalized
 * {@code float[][]} arrays.
 *
 * <p>This is used by {@link Mp3FileReader} and {@link OggVorbisFileReader}
 * for formats that require SPI-based decoders.</p>
 */
final class AudioSystemDecoder {

    private AudioSystemDecoder() {
        // utility class
    }

    /**
     * Decodes an audio file using {@link AudioSystem} and returns the
     * decoded audio data as normalized floats.
     *
     * @param path       the path to the audio file
     * @param formatName human-readable format name for error messages
     * @return the decoded audio result
     * @throws IOException if decoding fails or the format is unsupported
     */
    static AudioReadResult decode(Path path, String formatName) throws IOException {
        AudioInputStream sourceStream;
        try {
            sourceStream = AudioSystem.getAudioInputStream(path.toFile());
        } catch (UnsupportedAudioFileException e) {
            throw new IOException(
                    formatName + " decoding is not supported in this environment. "
                            + "Install a compatible audio SPI for " + formatName + " support.", e);
        }

        try (sourceStream) {
            AudioFormat sourceFormat = sourceStream.getFormat();

            // Request conversion to 16-bit signed PCM (little-endian)
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false // little-endian
            );

            AudioInputStream pcmStream;
            if (AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
                pcmStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
            } else if (sourceFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    || sourceFormat.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) {
                // Source is already PCM — use it directly
                pcmStream = sourceStream;
            } else {
                throw new IOException(
                        formatName + " decoding failed: no PCM conversion available for encoding "
                                + sourceFormat.getEncoding() + ". Install a compatible audio SPI.");
            }

            try (pcmStream) {
                AudioFormat format = pcmStream.getFormat();
                int channels = format.getChannels();
                int sampleRate = (int) format.getSampleRate();
                int sampleSizeInBits = format.getSampleSizeInBits();

                if (sampleSizeInBits <= 0) {
                    throw new IOException(
                            formatName + " decoding failed: unknown sample size from audio stream.");
                }

                // Read all PCM bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = pcmStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                byte[] pcmData = baos.toByteArray();

                int bytesPerSample = sampleSizeInBits / 8;
                int bytesPerFrame = channels * bytesPerSample;
                int numFrames = pcmData.length / bytesPerFrame;

                if (numFrames == 0) {
                    throw new IOException("No audio data decoded from " + formatName + " file: " + path);
                }

                // Convert PCM bytes to float[][]
                float[][] audioData = new float[channels][numFrames];
                ByteBuffer buf = ByteBuffer.wrap(pcmData).order(
                        format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

                for (int frame = 0; frame < numFrames; frame++) {
                    for (int ch = 0; ch < channels; ch++) {
                        float sample = switch (sampleSizeInBits) {
                            case 8 -> {
                                if (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) {
                                    yield ((buf.get() & 0xFF) - 128) / 128.0f;
                                } else {
                                    yield buf.get() / 128.0f;
                                }
                            }
                            case 16 -> buf.getShort() / 32768.0f;
                            case 24 -> {
                                int b0, b1, b2;
                                if (format.isBigEndian()) {
                                    b0 = buf.get() & 0xFF;
                                    b1 = buf.get() & 0xFF;
                                    b2 = buf.get() & 0xFF;
                                    int value = (b0 << 24) | (b1 << 16) | (b2 << 8);
                                    yield (value >> 8) / 8388608.0f;
                                } else {
                                    b0 = buf.get() & 0xFF;
                                    b1 = buf.get() & 0xFF;
                                    b2 = buf.get() & 0xFF;
                                    int value = (b2 << 24) | (b1 << 16) | (b0 << 8);
                                    yield (value >> 8) / 8388608.0f;
                                }
                            }
                            case 32 -> buf.getInt() / 2147483648.0f;
                            default -> throw new IOException(
                                    "Unsupported sample size: " + sampleSizeInBits + " bits");
                        };
                        audioData[ch][frame] = sample;
                    }
                }

                return new AudioReadResult(audioData, sampleRate, channels, sampleSizeInBits);
            }
        }
    }
}
