package com.benesquivelmusic.daw.core.plugin.clap;

import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * CLAP plugin host implementation using the Foreign Function &amp; Memory
 * API (JEP 454).
 *
 * <p>Manages the full lifecycle of a single CLAP plugin instance, including:
 * native library loading, plugin instantiation, audio processing, parameter
 * control, state persistence, and latency reporting.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct with the path to a {@code .clap} file and a plugin index</li>
 *   <li>{@link #initialize(PluginContext)} — loads the native library, creates
 *       the plugin instance, and queries extensions</li>
 *   <li>{@link #activate()} — activates the plugin for audio processing</li>
 *   <li>{@link #process(float[][], float[][], int)} — real-time audio callback</li>
 *   <li>{@link #deactivate()} — stops audio processing</li>
 *   <li>{@link #dispose()} — destroys the native plugin and frees all resources</li>
 * </ol>
 *
 * @see <a href="https://github.com/free-audio/clap">CLAP specification</a>
 */
public final class ClapPluginHost implements ExternalPluginHost {

    private final Path libraryPath;
    private final int pluginIndex;
    private final int inputChannels;
    private final int outputChannels;

    // Native state — managed via Arena lifecycle
    private Arena arena;
    private ClapBindings bindings;
    private MemorySegment factorySegment;
    private MemorySegment pluginSegment;
    private MemorySegment hostSegment;

    // Cached method handles for plugin function pointers
    private MethodHandle pluginInit;
    private MethodHandle pluginDestroy;
    private MethodHandle pluginActivate;
    private MethodHandle pluginDeactivate;
    private MethodHandle pluginStartProcessing;
    private MethodHandle pluginStopProcessing;
    private MethodHandle pluginReset;
    private MethodHandle pluginProcess;
    private MethodHandle pluginGetExtension;

    // Extension method handles (may be null if extension unsupported)
    private MethodHandle paramsCount;
    private MethodHandle paramsGetInfo;
    private MethodHandle paramsGetValue;
    private MethodHandle latencyGet;

    // Descriptor
    private PluginDescriptor descriptor;

    // State tracking
    private boolean initialized;
    private boolean activated;
    private boolean processing;
    private double sampleRate;
    private int bufferSize;

    // Cached parameter info
    private List<PluginParameter> cachedParameters;

    // Pre-allocated native buffers for audio processing
    private MemorySegment processStruct;
    private MemorySegment inputAudioBuffer;
    private MemorySegment outputAudioBuffer;
    private MemorySegment inputEventsStruct;
    private MemorySegment outputEventsStruct;

    /**
     * Creates a CLAP plugin host for the plugin at the given index within
     * the specified {@code .clap} library.
     *
     * @param libraryPath   path to the {@code .clap} shared library
     * @param pluginIndex   index of the plugin within the library's factory
     * @param inputChannels number of input audio channels (typically 2 for stereo)
     * @param outputChannels number of output audio channels (typically 2 for stereo)
     */
    public ClapPluginHost(Path libraryPath, int pluginIndex,
                          int inputChannels, int outputChannels) {
        this.libraryPath = Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        if (pluginIndex < 0) {
            throw new IllegalArgumentException("pluginIndex must be non-negative: " + pluginIndex);
        }
        if (inputChannels <= 0) {
            throw new IllegalArgumentException("inputChannels must be positive: " + inputChannels);
        }
        if (outputChannels <= 0) {
            throw new IllegalArgumentException("outputChannels must be positive: " + outputChannels);
        }
        this.pluginIndex = pluginIndex;
        this.inputChannels = inputChannels;
        this.outputChannels = outputChannels;
    }

    /**
     * Creates a stereo (2-in, 2-out) CLAP plugin host for the first plugin
     * in the library.
     *
     * @param libraryPath path to the {@code .clap} shared library
     */
    public ClapPluginHost(Path libraryPath) {
        this(libraryPath, 0, 2, 2);
    }

    @Override
    public ExternalPluginFormat getFormat() {
        return ExternalPluginFormat.CLAP;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        if (descriptor == null) {
            return new PluginDescriptor(
                    "clap:" + libraryPath.getFileName() + ":" + pluginIndex,
                    libraryPath.getFileName().toString(),
                    "0.0.0",
                    "Unknown",
                    PluginType.EFFECT);
        }
        return descriptor;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (initialized) {
            throw new ClapException("Plugin already initialized");
        }

        this.sampleRate = context.getSampleRate();
        this.bufferSize = context.getBufferSize();
        this.arena = Arena.ofConfined();

        // Load the native library
        bindings = new ClapBindings(libraryPath);
        if (!bindings.isAvailable()) {
            throw new ClapException("Failed to load CLAP library: " + libraryPath);
        }

        try {
            initEntry(context);
            initFactory();
            readDescriptor();
            createHostStruct(context);
            createPluginInstance();
            queryExtensions();
            allocateProcessBuffers();
            initialized = true;
            context.log("CLAP plugin initialized: " + descriptor.name());
        } catch (Throwable e) {
            dispose();
            throw new ClapException("Failed to initialize CLAP plugin: " + e.getMessage(), e);
        }
    }

    @Override
    public void activate() {
        requireInitialized();
        if (activated) {
            return;
        }

        try {
            boolean ok = (boolean) pluginActivate.invoke(
                    pluginSegment, sampleRate, (int) bufferSize, (int) bufferSize);
            if (!ok) {
                throw new ClapException("Plugin activation failed");
            }

            ok = (boolean) pluginStartProcessing.invoke(pluginSegment);
            if (!ok) {
                // Some plugins may not implement start_processing; not fatal
            }
            activated = true;
            processing = true;
        } catch (ClapException e) {
            throw e;
        } catch (Throwable e) {
            throw new ClapException("Plugin activation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void deactivate() {
        if (!activated) {
            return;
        }

        try {
            if (processing) {
                pluginStopProcessing.invoke(pluginSegment);
                processing = false;
            }
            pluginDeactivate.invoke(pluginSegment);
            activated = false;
        } catch (Throwable e) {
            throw new ClapException("Plugin deactivation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void dispose() {
        try {
            if (activated) {
                deactivate();
            }
            if (pluginDestroy != null && pluginSegment != null) {
                pluginDestroy.invoke(pluginSegment);
            }
        } catch (Throwable _) {
            // Best-effort cleanup
        } finally {
            pluginSegment = null;
            factorySegment = null;
            hostSegment = null;
            bindings = null;
            initialized = false;
            activated = false;
            processing = false;
            cachedParameters = null;
            if (arena != null) {
                arena.close();
                arena = null;
            }
        }
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (!processing) {
            // Pass-through when not processing
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        try {
            writeInputToNative(inputBuffer, numFrames);
            setupProcessStruct(numFrames);

            int status = (int) pluginProcess.invoke(pluginSegment, processStruct);
            if (status == ClapBindings.CLAP_PROCESS_ERROR) {
                copyBuffer(inputBuffer, outputBuffer, numFrames);
                return;
            }

            readOutputFromNative(outputBuffer, numFrames);
        } catch (Throwable e) {
            // On error, pass through
            copyBuffer(inputBuffer, outputBuffer, numFrames);
        }
    }

    @Override
    public void reset() {
        if (pluginReset != null && pluginSegment != null && initialized) {
            try {
                pluginReset.invoke(pluginSegment);
            } catch (Throwable _) {
                // Best-effort reset
            }
        }
    }

    @Override
    public int getInputChannelCount() {
        return inputChannels;
    }

    @Override
    public int getOutputChannelCount() {
        return outputChannels;
    }

    @Override
    public List<PluginParameter> getParameters() {
        if (cachedParameters != null) {
            return cachedParameters;
        }
        if (paramsCount == null || paramsGetInfo == null) {
            cachedParameters = List.of();
            return cachedParameters;
        }

        try {
            int count = (int) paramsCount.invoke(pluginSegment);
            var params = new ArrayList<PluginParameter>(count);

            for (int i = 0; i < count; i++) {
                MemorySegment infoSegment = arena.allocate(ClapBindings.CLAP_PARAM_INFO_LAYOUT);
                boolean ok = (boolean) paramsGetInfo.invoke(pluginSegment, i, infoSegment);
                if (ok) {
                    int id = (int) ClapBindings.PARAM_INFO_ID.get(infoSegment, 0L);
                    long nameOffset = ClapBindings.CLAP_PARAM_INFO_LAYOUT
                            .byteOffset(MemoryLayout.PathElement.groupElement("name"));
                    String name = ClapBindings.readFixedString(
                            infoSegment, nameOffset, ClapBindings.CLAP_NAME_SIZE);
                    double min = (double) ClapBindings.PARAM_INFO_MIN.get(infoSegment, 0L);
                    double max = (double) ClapBindings.PARAM_INFO_MAX.get(infoSegment, 0L);
                    double def = (double) ClapBindings.PARAM_INFO_DEFAULT.get(infoSegment, 0L);

                    if (name.isEmpty()) {
                        name = "Parameter " + id;
                    }
                    params.add(new PluginParameter(id, name, min, max, def));
                }
            }

            cachedParameters = Collections.unmodifiableList(params);
            return cachedParameters;
        } catch (Throwable e) {
            throw new ClapException("Failed to get parameters: " + e.getMessage(), e);
        }
    }

    @Override
    public double getParameterValue(int parameterId) {
        if (paramsGetValue == null) {
            throw new IllegalArgumentException("Plugin does not support parameters");
        }

        try {
            MemorySegment valueOut = arena.allocate(ValueLayout.JAVA_DOUBLE);
            boolean ok = (boolean) paramsGetValue.invoke(pluginSegment, parameterId, valueOut);
            if (!ok) {
                throw new IllegalArgumentException("Parameter not found: " + parameterId);
            }
            return valueOut.get(ValueLayout.JAVA_DOUBLE, 0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable e) {
            throw new ClapException("Failed to get parameter value: " + e.getMessage(), e);
        }
    }

    @Override
    public void setParameterValue(int parameterId, double value) {
        // Parameter value changes in CLAP are typically done via events in the
        // process call. For now, validate the parameter exists.
        var params = getParameters();
        var param = params.stream()
                .filter(p -> p.id() == parameterId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parameter not found: " + parameterId));

        if (value < param.minValue() || value > param.maxValue()) {
            throw new IllegalArgumentException(
                    "Value %f outside range [%f, %f] for parameter %s"
                            .formatted(value, param.minValue(), param.maxValue(), param.name()));
        }

        // TODO: Queue parameter change event for next process() call
    }

    @Override
    public int getLatencySamples() {
        if (latencyGet == null || pluginSegment == null) {
            return 0;
        }
        try {
            return (int) latencyGet.invoke(pluginSegment);
        } catch (Throwable _) {
            return 0;
        }
    }

    @Override
    public byte[] saveState() {
        // State save requires implementing clap_ostream_t with FFM upcall stubs.
        // Returns empty array until the full stream implementation is added.
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        Objects.requireNonNull(state, "state must not be null");
        // State load requires implementing clap_istream_t with FFM upcall stubs.
        // No-op until the full stream implementation is added.
    }

    /**
     * Returns the path to the CLAP plugin library.
     *
     * @return the library path
     */
    public Path getLibraryPath() {
        return libraryPath;
    }

    /**
     * Returns the plugin index within the factory.
     *
     * @return the plugin index
     */
    public int getPluginIndex() {
        return pluginIndex;
    }

    /**
     * Returns whether the plugin is currently initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns whether the plugin is currently activated and processing audio.
     *
     * @return true if activated
     */
    public boolean isActivated() {
        return activated;
    }

    // -----------------------------------------------------------------------
    // Internal initialization helpers
    // -----------------------------------------------------------------------

    private void initEntry(PluginContext context) throws Throwable {
        MemorySegment entrySegment = bindings.getEntrySegment();

        MemorySegment initFnPtr = (MemorySegment) ClapBindings.ENTRY_INIT
                .get(entrySegment, 0L);
        MethodHandle entryInit = ClapBindings.downcallHandle(
                initFnPtr, ClapBindings.ENTRY_INIT_DESC);

        MemorySegment pathStr = arena.allocateFrom(libraryPath.toAbsolutePath().toString());
        boolean ok = (boolean) entryInit.invoke(pathStr);
        if (!ok) {
            throw new ClapException("clap_entry.init() returned false");
        }
    }

    private void initFactory() throws Throwable {
        MemorySegment entrySegment = bindings.getEntrySegment();

        MemorySegment getFactoryFnPtr = (MemorySegment) ClapBindings.ENTRY_GET_FACTORY
                .get(entrySegment, 0L);
        MethodHandle getFactory = ClapBindings.downcallHandle(
                getFactoryFnPtr, ClapBindings.ENTRY_GET_FACTORY_DESC);

        MemorySegment factoryId = arena.allocateFrom(ClapBindings.CLAP_PLUGIN_FACTORY_ID);
        MemorySegment rawFactory = (MemorySegment) getFactory.invoke(factoryId);

        if (rawFactory.equals(MemorySegment.NULL)) {
            throw new ClapException("Plugin factory not found");
        }

        factorySegment = rawFactory.reinterpret(ClapBindings.CLAP_PLUGIN_FACTORY_LAYOUT.byteSize());
    }

    private void readDescriptor() throws Throwable {
        MemorySegment getDescFnPtr = (MemorySegment) ClapBindings.FACTORY_GET_PLUGIN_DESCRIPTOR
                .get(factorySegment, 0L);
        MethodHandle getDesc = ClapBindings.downcallHandle(
                getDescFnPtr, ClapBindings.FACTORY_GET_PLUGIN_DESCRIPTOR_DESC);

        MemorySegment rawDesc = (MemorySegment) getDesc.invoke(factorySegment, pluginIndex);
        if (rawDesc.equals(MemorySegment.NULL)) {
            throw new ClapException("Plugin descriptor not found at index " + pluginIndex);
        }

        MemorySegment descSegment = rawDesc.reinterpret(
                ClapBindings.CLAP_PLUGIN_DESCRIPTOR_LAYOUT.byteSize());

        MemorySegment idPtr = (MemorySegment) ClapBindings.DESCRIPTOR_ID.get(descSegment, 0L);
        MemorySegment namePtr = (MemorySegment) ClapBindings.DESCRIPTOR_NAME.get(descSegment, 0L);
        MemorySegment vendorPtr = (MemorySegment) ClapBindings.DESCRIPTOR_VENDOR.get(descSegment, 0L);
        MemorySegment versionPtr = (MemorySegment) ClapBindings.DESCRIPTOR_VERSION.get(descSegment, 0L);

        String id = ClapBindings.readString(idPtr);
        String name = ClapBindings.readString(namePtr);
        String vendor = ClapBindings.readString(vendorPtr);
        String version = ClapBindings.readString(versionPtr);

        if (id.isEmpty()) id = "clap.unknown";
        if (name.isEmpty()) name = "Unknown CLAP Plugin";
        if (vendor.isEmpty()) vendor = "Unknown";
        if (version.isEmpty()) version = "0.0.0";

        this.descriptor = new PluginDescriptor(id, name, version, vendor, PluginType.EFFECT);
    }

    private void createHostStruct(PluginContext context) {
        hostSegment = arena.allocate(ClapBindings.CLAP_HOST_LAYOUT);

        // Set version
        long versionOffset = 0;
        hostSegment.set(ValueLayout.JAVA_INT, versionOffset, ClapBindings.CLAP_VERSION_MAJOR);
        hostSegment.set(ValueLayout.JAVA_INT, versionOffset + 4, ClapBindings.CLAP_VERSION_MINOR);
        hostSegment.set(ValueLayout.JAVA_INT, versionOffset + 8, ClapBindings.CLAP_VERSION_REVISION);

        // Set host metadata strings
        long hostDataOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("host_data"));
        hostSegment.set(ValueLayout.ADDRESS, hostDataOffset, MemorySegment.NULL);

        MemorySegment hostName = arena.allocateFrom("Java DAW");
        long nameOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("name"));
        hostSegment.set(ValueLayout.ADDRESS, nameOffset, hostName);

        MemorySegment hostVendor = arena.allocateFrom("Ben Esquivel Music");
        long vendorOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("vendor"));
        hostSegment.set(ValueLayout.ADDRESS, vendorOffset, hostVendor);

        MemorySegment hostUrl = arena.allocateFrom("https://github.com/Ben-Esquivel-Music/java-digital-audio-workstation");
        long urlOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("url"));
        hostSegment.set(ValueLayout.ADDRESS, urlOffset, hostUrl);

        MemorySegment hostVersion = arena.allocateFrom("0.1.0");
        long versionStrOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("version"));
        hostSegment.set(ValueLayout.ADDRESS, versionStrOffset, hostVersion);

        // Create no-op upcall stubs for host callbacks
        FunctionDescriptor callbackDesc = ClapBindings.HOST_CALLBACK_DESC;
        MemorySegment requestRestart = ClapBindings.linker().upcallStub(
                NO_OP_HOST_CALLBACK, callbackDesc, arena);
        MemorySegment requestProcess = ClapBindings.linker().upcallStub(
                NO_OP_HOST_CALLBACK, callbackDesc, arena);
        MemorySegment requestCallback = ClapBindings.linker().upcallStub(
                NO_OP_HOST_CALLBACK, callbackDesc, arena);

        long restartOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("request_restart"));
        hostSegment.set(ValueLayout.ADDRESS, restartOffset, requestRestart);

        long processOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("request_process"));
        hostSegment.set(ValueLayout.ADDRESS, processOffset, requestProcess);

        long callbackOffset = ClapBindings.CLAP_HOST_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("request_callback"));
        hostSegment.set(ValueLayout.ADDRESS, callbackOffset, requestCallback);
    }

    private void createPluginInstance() throws Throwable {
        MemorySegment createFnPtr = (MemorySegment) ClapBindings.FACTORY_CREATE_PLUGIN
                .get(factorySegment, 0L);
        MethodHandle createPlugin = ClapBindings.downcallHandle(
                createFnPtr, ClapBindings.FACTORY_CREATE_PLUGIN_DESC);

        MemorySegment pluginId = arena.allocateFrom(descriptor.id());
        MemorySegment rawPlugin = (MemorySegment) createPlugin.invoke(
                factorySegment, hostSegment, pluginId);

        if (rawPlugin.equals(MemorySegment.NULL)) {
            throw new ClapException("Failed to create plugin instance");
        }

        pluginSegment = rawPlugin.reinterpret(ClapBindings.CLAP_PLUGIN_LAYOUT.byteSize());

        // Bind plugin function pointers
        bindPluginFunctions();

        // Call plugin init
        boolean ok = (boolean) pluginInit.invoke(pluginSegment);
        if (!ok) {
            throw new ClapException("Plugin init() returned false");
        }
    }

    private void bindPluginFunctions() {
        pluginInit = bindPluginFn("init", ClapBindings.PLUGIN_INIT,
                ClapBindings.PLUGIN_INIT_DESC);
        pluginDestroy = bindPluginFn("destroy", ClapBindings.PLUGIN_DESTROY,
                ClapBindings.PLUGIN_DESTROY_DESC);
        pluginActivate = bindPluginFn("activate", ClapBindings.PLUGIN_ACTIVATE,
                ClapBindings.PLUGIN_ACTIVATE_DESC);
        pluginDeactivate = bindPluginFn("deactivate", ClapBindings.PLUGIN_DEACTIVATE,
                ClapBindings.PLUGIN_DEACTIVATE_DESC);
        pluginStartProcessing = bindPluginFn("start_processing",
                ClapBindings.PLUGIN_START_PROCESSING, ClapBindings.PLUGIN_START_PROCESSING_DESC);
        pluginStopProcessing = bindPluginFn("stop_processing",
                ClapBindings.PLUGIN_STOP_PROCESSING, ClapBindings.PLUGIN_STOP_PROCESSING_DESC);
        pluginReset = bindPluginFn("reset", ClapBindings.PLUGIN_RESET,
                ClapBindings.PLUGIN_RESET_DESC);
        pluginProcess = bindPluginFn("process", ClapBindings.PLUGIN_PROCESS,
                ClapBindings.PLUGIN_PROCESS_DESC);
        pluginGetExtension = bindPluginFn("get_extension", ClapBindings.PLUGIN_GET_EXTENSION,
                ClapBindings.PLUGIN_GET_EXTENSION_DESC);
    }

    private MethodHandle bindPluginFn(String name, java.lang.invoke.VarHandle varHandle,
                                      FunctionDescriptor descriptor) {
        MemorySegment fnPtr = (MemorySegment) varHandle.get(pluginSegment, 0L);
        if (fnPtr.equals(MemorySegment.NULL)) {
            throw new ClapException("Plugin function pointer is NULL: " + name);
        }
        return ClapBindings.downcallHandle(fnPtr, descriptor);
    }

    private void queryExtensions() throws Throwable {
        // Query params extension
        MemorySegment paramsId = arena.allocateFrom(ClapBindings.CLAP_EXT_PARAMS);
        MemorySegment paramsExt = (MemorySegment) pluginGetExtension.invoke(
                pluginSegment, paramsId);
        if (!paramsExt.equals(MemorySegment.NULL)) {
            MemorySegment paramsStruct = paramsExt.reinterpret(
                    ClapBindings.CLAP_PLUGIN_PARAMS_LAYOUT.byteSize());
            MemorySegment countFn = (MemorySegment) ClapBindings.PARAMS_COUNT
                    .get(paramsStruct, 0L);
            MemorySegment getInfoFn = (MemorySegment) ClapBindings.PARAMS_GET_INFO
                    .get(paramsStruct, 0L);
            MemorySegment getValueFn = (MemorySegment) ClapBindings.PARAMS_GET_VALUE
                    .get(paramsStruct, 0L);

            if (!countFn.equals(MemorySegment.NULL)) {
                paramsCount = ClapBindings.downcallHandle(countFn, ClapBindings.PARAMS_COUNT_DESC);
            }
            if (!getInfoFn.equals(MemorySegment.NULL)) {
                paramsGetInfo = ClapBindings.downcallHandle(
                        getInfoFn, ClapBindings.PARAMS_GET_INFO_DESC);
            }
            if (!getValueFn.equals(MemorySegment.NULL)) {
                paramsGetValue = ClapBindings.downcallHandle(
                        getValueFn, ClapBindings.PARAMS_GET_VALUE_DESC);
            }
        }

        // Query latency extension
        MemorySegment latencyId = arena.allocateFrom(ClapBindings.CLAP_EXT_LATENCY);
        MemorySegment latencyExt = (MemorySegment) pluginGetExtension.invoke(
                pluginSegment, latencyId);
        if (!latencyExt.equals(MemorySegment.NULL)) {
            MemorySegment latencyStruct = latencyExt.reinterpret(
                    ClapBindings.CLAP_PLUGIN_LATENCY_LAYOUT.byteSize());
            MemorySegment getFn = (MemorySegment) ClapBindings.LATENCY_GET
                    .get(latencyStruct, 0L);
            if (!getFn.equals(MemorySegment.NULL)) {
                latencyGet = ClapBindings.downcallHandle(getFn, ClapBindings.LATENCY_GET_DESC);
            }
        }
    }

    private void allocateProcessBuffers() {
        // Allocate clap_process_t
        processStruct = arena.allocate(ClapBindings.CLAP_PROCESS_LAYOUT);

        // Allocate input audio buffer struct
        inputAudioBuffer = arena.allocate(ClapBindings.CLAP_AUDIO_BUFFER_LAYOUT);
        ClapBindings.AUDIO_BUFFER_CHANNEL_COUNT.set(inputAudioBuffer, 0L, inputChannels);

        // Allocate output audio buffer struct
        outputAudioBuffer = arena.allocate(ClapBindings.CLAP_AUDIO_BUFFER_LAYOUT);
        ClapBindings.AUDIO_BUFFER_CHANNEL_COUNT.set(outputAudioBuffer, 0L, outputChannels);

        // Allocate empty input events (no events)
        inputEventsStruct = arena.allocate(ClapBindings.CLAP_INPUT_EVENTS_LAYOUT);
        MemorySegment sizeStub = ClapBindings.linker().upcallStub(
                EVENTS_SIZE_ZERO, ClapBindings.EVENTS_SIZE_DESC, arena);
        MemorySegment getStub = ClapBindings.linker().upcallStub(
                EVENTS_GET_NULL, ClapBindings.EVENTS_GET_DESC, arena);
        long sizeOffset = ClapBindings.CLAP_INPUT_EVENTS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("size"));
        long getOffset = ClapBindings.CLAP_INPUT_EVENTS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("get"));
        inputEventsStruct.set(ValueLayout.ADDRESS, sizeOffset, sizeStub);
        inputEventsStruct.set(ValueLayout.ADDRESS, getOffset, getStub);

        // Allocate empty output events
        outputEventsStruct = arena.allocate(ClapBindings.CLAP_OUTPUT_EVENTS_LAYOUT);
        MemorySegment tryPushStub = ClapBindings.linker().upcallStub(
                EVENTS_TRY_PUSH_FALSE, ClapBindings.EVENTS_TRY_PUSH_DESC, arena);
        long tryPushOffset = ClapBindings.CLAP_OUTPUT_EVENTS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("try_push"));
        outputEventsStruct.set(ValueLayout.ADDRESS, tryPushOffset, tryPushStub);
    }

    private void writeInputToNative(float[][] inputBuffer, int numFrames) {
        int channels = Math.min(inputBuffer.length, inputChannels);

        // Allocate array of channel pointers
        MemorySegment channelPtrs = arena.allocate(ValueLayout.ADDRESS, channels);
        for (int ch = 0; ch < channels; ch++) {
            int framesToCopy = Math.min(inputBuffer[ch].length, numFrames);
            MemorySegment channelData = arena.allocate(ValueLayout.JAVA_FLOAT, numFrames);
            MemorySegment.copy(inputBuffer[ch], 0, channelData,
                    ValueLayout.JAVA_FLOAT, 0, framesToCopy);
            channelPtrs.setAtIndex(ValueLayout.ADDRESS, ch, channelData);
        }

        ClapBindings.AUDIO_BUFFER_DATA32.set(inputAudioBuffer, 0L, channelPtrs);
    }

    private void setupProcessStruct(int numFrames) {
        long framesOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("frames_count"));
        processStruct.set(ValueLayout.JAVA_INT, framesOffset, numFrames);

        long transportOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("transport"));
        processStruct.set(ValueLayout.ADDRESS, transportOffset, MemorySegment.NULL);

        long inputsOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("audio_inputs"));
        processStruct.set(ValueLayout.ADDRESS, inputsOffset, inputAudioBuffer);

        long outputsOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("audio_outputs"));
        // Allocate fresh output channel pointers
        int channels = outputChannels;
        MemorySegment outChannelPtrs = arena.allocate(ValueLayout.ADDRESS, channels);
        MemorySegment[] outChannelData = new MemorySegment[channels];
        for (int ch = 0; ch < channels; ch++) {
            outChannelData[ch] = arena.allocate(ValueLayout.JAVA_FLOAT, numFrames);
            outChannelPtrs.setAtIndex(ValueLayout.ADDRESS, ch, outChannelData[ch]);
        }
        ClapBindings.AUDIO_BUFFER_DATA32.set(outputAudioBuffer, 0L, outChannelPtrs);
        processStruct.set(ValueLayout.ADDRESS, outputsOffset, outputAudioBuffer);

        long inputsCountOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("audio_inputs_count"));
        processStruct.set(ValueLayout.JAVA_INT, inputsCountOffset, 1);

        long outputsCountOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("audio_outputs_count"));
        processStruct.set(ValueLayout.JAVA_INT, outputsCountOffset, 1);

        long inEventsOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("in_events"));
        processStruct.set(ValueLayout.ADDRESS, inEventsOffset, inputEventsStruct);

        long outEventsOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("out_events"));
        processStruct.set(ValueLayout.ADDRESS, outEventsOffset, outputEventsStruct);
    }

    private void readOutputFromNative(float[][] outputBuffer, int numFrames) {
        MemorySegment outChannelPtrs = (MemorySegment) ClapBindings.AUDIO_BUFFER_DATA32
                .get(outputAudioBuffer, 0L);
        outChannelPtrs = outChannelPtrs.reinterpret(
                (long) outputChannels * ValueLayout.ADDRESS.byteSize());

        int channels = Math.min(outputBuffer.length, outputChannels);
        for (int ch = 0; ch < channels; ch++) {
            MemorySegment channelData = outChannelPtrs.getAtIndex(ValueLayout.ADDRESS, ch);
            channelData = channelData.reinterpret((long) numFrames * ValueLayout.JAVA_FLOAT.byteSize());
            int framesToCopy = Math.min(outputBuffer[ch].length, numFrames);
            MemorySegment.copy(channelData, ValueLayout.JAVA_FLOAT, 0,
                    outputBuffer[ch], 0, framesToCopy);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new ClapException("Plugin is not initialized");
        }
    }

    private static void copyBuffer(float[][] src, float[][] dst, int numFrames) {
        int channels = Math.min(src.length, dst.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
        }
    }

    // -----------------------------------------------------------------------
    // Upcall targets for host callbacks and events
    // -----------------------------------------------------------------------

    /** No-op host callback: {@code void callback(const clap_host_t*)} */
    @SuppressWarnings("unused")
    private static void noOpHostCallback(MemorySegment host) {
        // No-op — host requests are not yet handled
    }

    /** Empty input events: always returns size 0. */
    @SuppressWarnings("unused")
    private static int eventsSizeZero(MemorySegment events) {
        return 0;
    }

    /** Empty input events: always returns NULL. */
    @SuppressWarnings("unused")
    private static MemorySegment eventsGetNull(MemorySegment events, int index) {
        return MemorySegment.NULL;
    }

    /** Empty output events: always returns false (discard events). */
    @SuppressWarnings("unused")
    private static boolean eventsTryPushFalse(MemorySegment events, MemorySegment event) {
        return false;
    }

    // MethodHandles for upcall targets
    private static final MethodHandle NO_OP_HOST_CALLBACK;
    private static final MethodHandle EVENTS_SIZE_ZERO;
    private static final MethodHandle EVENTS_GET_NULL;
    private static final MethodHandle EVENTS_TRY_PUSH_FALSE;

    static {
        try {
            var lookup = java.lang.invoke.MethodHandles.lookup();
            NO_OP_HOST_CALLBACK = lookup.findStatic(ClapPluginHost.class, "noOpHostCallback",
                    java.lang.invoke.MethodType.methodType(void.class, MemorySegment.class));
            EVENTS_SIZE_ZERO = lookup.findStatic(ClapPluginHost.class, "eventsSizeZero",
                    java.lang.invoke.MethodType.methodType(int.class, MemorySegment.class));
            EVENTS_GET_NULL = lookup.findStatic(ClapPluginHost.class, "eventsGetNull",
                    java.lang.invoke.MethodType.methodType(MemorySegment.class,
                            MemorySegment.class, int.class));
            EVENTS_TRY_PUSH_FALSE = lookup.findStatic(ClapPluginHost.class, "eventsTryPushFalse",
                    java.lang.invoke.MethodType.methodType(boolean.class,
                            MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Read the number of frames from setupProcessStruct
    private int readNumFrames() {
        long framesOffset = ClapBindings.CLAP_PROCESS_LAYOUT
                .byteOffset(MemoryLayout.PathElement.groupElement("frames_count"));
        return processStruct.get(ValueLayout.JAVA_INT, framesOffset);
    }
}
