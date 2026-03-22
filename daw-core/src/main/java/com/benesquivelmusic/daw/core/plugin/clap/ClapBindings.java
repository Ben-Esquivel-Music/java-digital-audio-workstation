package com.benesquivelmusic.daw.core.plugin.clap;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;

/**
 * Raw FFM (Foreign Function &amp; Memory API — JEP 454) bindings for the
 * CLAP (CLever Audio Plugin) C API.
 *
 * <p>This class provides struct layouts, function descriptors, and helper
 * methods for interacting with CLAP plugin shared libraries at runtime via
 * {@link Linker} and {@link SymbolLookup} — no JNI required.</p>
 *
 * <h2>CLAP Entry Point</h2>
 * <p>Every CLAP plugin exposes a global {@code clap_entry} symbol of type
 * {@code clap_plugin_entry_t}. This struct contains function pointers for
 * {@code init}, {@code deinit}, and {@code get_factory}.</p>
 *
 * <h2>Key Structs</h2>
 * <ul>
 *   <li>{@code clap_version_t} — version triple (major, minor, revision)</li>
 *   <li>{@code clap_plugin_entry_t} — library entry point</li>
 *   <li>{@code clap_plugin_factory_t} — plugin enumeration and instantiation</li>
 *   <li>{@code clap_plugin_descriptor_t} — plugin metadata</li>
 *   <li>{@code clap_host_t} — host callbacks provided to plugins</li>
 *   <li>{@code clap_plugin_t} — individual plugin instance</li>
 *   <li>{@code clap_audio_buffer_t} — audio I/O buffer</li>
 *   <li>{@code clap_process_t} — audio processing context</li>
 * </ul>
 *
 * @see <a href="https://github.com/free-audio/clap">CLAP specification</a>
 */
public final class ClapBindings {

    // -----------------------------------------------------------------------
    // CLAP constants
    // -----------------------------------------------------------------------

    /** Current CLAP major version supported by this bridge. */
    public static final int CLAP_VERSION_MAJOR = 1;

    /** Current CLAP minor version supported by this bridge. */
    public static final int CLAP_VERSION_MINOR = 2;

    /** Current CLAP revision supported by this bridge. */
    public static final int CLAP_VERSION_REVISION = 2;

    /** Factory identifier for the standard plugin factory. */
    public static final String CLAP_PLUGIN_FACTORY_ID = "clap.plugin-factory";

    /** Extension identifier for the params extension. */
    public static final String CLAP_EXT_PARAMS = "clap.params";

    /** Extension identifier for the latency extension. */
    public static final String CLAP_EXT_LATENCY = "clap.latency";

    /** Extension identifier for the state extension. */
    public static final String CLAP_EXT_STATE = "clap.state";

    /** Process status: continue processing. */
    public static final int CLAP_PROCESS_CONTINUE = 0;

    /** Process status: continue if not quiet. */
    public static final int CLAP_PROCESS_CONTINUE_IF_NOT_QUIET = 1;

    /** Process status: tail finished. */
    public static final int CLAP_PROCESS_TAIL = 2;

    /** Process status: sleep (plugin has no more output). */
    public static final int CLAP_PROCESS_SLEEP = 3;

    /** Process status: error. */
    public static final int CLAP_PROCESS_ERROR = -1;

    // -----------------------------------------------------------------------
    // clap_version_t layout: { uint32_t major; uint32_t minor; uint32_t revision; }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_version_t}. */
    public static final MemoryLayout CLAP_VERSION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("major"),
            ValueLayout.JAVA_INT.withName("minor"),
            ValueLayout.JAVA_INT.withName("revision")
    );

    static final VarHandle VERSION_MAJOR =
            CLAP_VERSION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("major"));
    static final VarHandle VERSION_MINOR =
            CLAP_VERSION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("minor"));
    static final VarHandle VERSION_REVISION =
            CLAP_VERSION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("revision"));

    // -----------------------------------------------------------------------
    // clap_plugin_entry_t layout:
    //   { clap_version_t clap_version;
    //     bool (*init)(const char *plugin_path);
    //     void (*deinit)(void);
    //     const void *(*get_factory)(const char *factory_id); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_entry_t}. */
    public static final MemoryLayout CLAP_PLUGIN_ENTRY_LAYOUT = MemoryLayout.structLayout(
            CLAP_VERSION_LAYOUT.withName("clap_version"),
            MemoryLayout.paddingLayout(4),  // alignment padding
            ValueLayout.ADDRESS.withName("init"),
            ValueLayout.ADDRESS.withName("deinit"),
            ValueLayout.ADDRESS.withName("get_factory")
    );

    static final VarHandle ENTRY_INIT =
            CLAP_PLUGIN_ENTRY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("init"));
    static final VarHandle ENTRY_DEINIT =
            CLAP_PLUGIN_ENTRY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("deinit"));
    static final VarHandle ENTRY_GET_FACTORY =
            CLAP_PLUGIN_ENTRY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_factory"));

    // -----------------------------------------------------------------------
    // clap_plugin_factory_t layout (function pointers):
    //   { uint32_t (*get_plugin_count)(const factory*);
    //     const clap_plugin_descriptor_t *(*get_plugin_descriptor)(const factory*, uint32_t);
    //     const clap_plugin_t *(*create_plugin)(const factory*, const clap_host_t*, const char*); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_factory_t}. */
    public static final MemoryLayout CLAP_PLUGIN_FACTORY_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("get_plugin_count"),
            ValueLayout.ADDRESS.withName("get_plugin_descriptor"),
            ValueLayout.ADDRESS.withName("create_plugin")
    );

    static final VarHandle FACTORY_GET_PLUGIN_COUNT =
            CLAP_PLUGIN_FACTORY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_plugin_count"));
    static final VarHandle FACTORY_GET_PLUGIN_DESCRIPTOR =
            CLAP_PLUGIN_FACTORY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_plugin_descriptor"));
    static final VarHandle FACTORY_CREATE_PLUGIN =
            CLAP_PLUGIN_FACTORY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("create_plugin"));

    // -----------------------------------------------------------------------
    // clap_plugin_descriptor_t layout:
    //   { clap_version_t clap_version;
    //     const char *id; const char *name; const char *vendor;
    //     const char *url; const char *manual_url; const char *support_url;
    //     const char *version; const char *description;
    //     const char *const *features; }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_descriptor_t}. */
    public static final MemoryLayout CLAP_PLUGIN_DESCRIPTOR_LAYOUT = MemoryLayout.structLayout(
            CLAP_VERSION_LAYOUT.withName("clap_version"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("id"),
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.ADDRESS.withName("vendor"),
            ValueLayout.ADDRESS.withName("url"),
            ValueLayout.ADDRESS.withName("manual_url"),
            ValueLayout.ADDRESS.withName("support_url"),
            ValueLayout.ADDRESS.withName("version"),
            ValueLayout.ADDRESS.withName("description"),
            ValueLayout.ADDRESS.withName("features")
    );

    static final VarHandle DESCRIPTOR_ID =
            CLAP_PLUGIN_DESCRIPTOR_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("id"));
    static final VarHandle DESCRIPTOR_NAME =
            CLAP_PLUGIN_DESCRIPTOR_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("name"));
    static final VarHandle DESCRIPTOR_VENDOR =
            CLAP_PLUGIN_DESCRIPTOR_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("vendor"));
    static final VarHandle DESCRIPTOR_VERSION =
            CLAP_PLUGIN_DESCRIPTOR_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("version"));

    // -----------------------------------------------------------------------
    // clap_host_t layout:
    //   { clap_version_t clap_version;
    //     void *host_data; const char *name; const char *vendor;
    //     const char *url; const char *version;
    //     void (*request_restart)(const host*);
    //     void (*request_process)(const host*);
    //     void (*request_callback)(const host*); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_host_t}. */
    public static final MemoryLayout CLAP_HOST_LAYOUT = MemoryLayout.structLayout(
            CLAP_VERSION_LAYOUT.withName("clap_version"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("host_data"),
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.ADDRESS.withName("vendor"),
            ValueLayout.ADDRESS.withName("url"),
            ValueLayout.ADDRESS.withName("version"),
            ValueLayout.ADDRESS.withName("request_restart"),
            ValueLayout.ADDRESS.withName("request_process"),
            ValueLayout.ADDRESS.withName("request_callback")
    );

    // -----------------------------------------------------------------------
    // clap_plugin_t layout:
    //   { const clap_plugin_descriptor_t *desc; void *plugin_data;
    //     bool (*init)(const plugin*); void (*destroy)(const plugin*);
    //     bool (*activate)(const plugin*, double, uint32_t, uint32_t);
    //     void (*deactivate)(const plugin*);
    //     bool (*start_processing)(const plugin*);
    //     void (*stop_processing)(const plugin*);
    //     void (*reset)(const plugin*);
    //     clap_process_status (*process)(const plugin*, const clap_process_t*);
    //     const void *(*get_extension)(const plugin*, const char*);
    //     void (*on_main_thread)(const plugin*); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_t}. */
    public static final MemoryLayout CLAP_PLUGIN_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("desc"),
            ValueLayout.ADDRESS.withName("plugin_data"),
            ValueLayout.ADDRESS.withName("init"),
            ValueLayout.ADDRESS.withName("destroy"),
            ValueLayout.ADDRESS.withName("activate"),
            ValueLayout.ADDRESS.withName("deactivate"),
            ValueLayout.ADDRESS.withName("start_processing"),
            ValueLayout.ADDRESS.withName("stop_processing"),
            ValueLayout.ADDRESS.withName("reset"),
            ValueLayout.ADDRESS.withName("process"),
            ValueLayout.ADDRESS.withName("get_extension"),
            ValueLayout.ADDRESS.withName("on_main_thread")
    );

    static final VarHandle PLUGIN_DESC =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("desc"));
    static final VarHandle PLUGIN_INIT =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("init"));
    static final VarHandle PLUGIN_DESTROY =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("destroy"));
    static final VarHandle PLUGIN_ACTIVATE =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("activate"));
    static final VarHandle PLUGIN_DEACTIVATE =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("deactivate"));
    static final VarHandle PLUGIN_START_PROCESSING =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("start_processing"));
    static final VarHandle PLUGIN_STOP_PROCESSING =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("stop_processing"));
    static final VarHandle PLUGIN_RESET =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("reset"));
    static final VarHandle PLUGIN_PROCESS =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("process"));
    static final VarHandle PLUGIN_GET_EXTENSION =
            CLAP_PLUGIN_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_extension"));

    // -----------------------------------------------------------------------
    // clap_audio_buffer_t layout:
    //   { float **data32; double **data64; uint32_t channel_count;
    //     uint32_t latency; uint64_t constant_mask; }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_audio_buffer_t}. */
    public static final MemoryLayout CLAP_AUDIO_BUFFER_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("data32"),
            ValueLayout.ADDRESS.withName("data64"),
            ValueLayout.JAVA_INT.withName("channel_count"),
            ValueLayout.JAVA_INT.withName("latency"),
            ValueLayout.JAVA_LONG.withName("constant_mask")
    );

    static final VarHandle AUDIO_BUFFER_DATA32 =
            CLAP_AUDIO_BUFFER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("data32"));
    static final VarHandle AUDIO_BUFFER_CHANNEL_COUNT =
            CLAP_AUDIO_BUFFER_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("channel_count"));

    // -----------------------------------------------------------------------
    // clap_process_t layout:
    //   { int64_t steady_time; uint32_t frames_count;
    //     const clap_event_transport_t *transport;
    //     const clap_audio_buffer_t *audio_inputs;
    //     clap_audio_buffer_t *audio_outputs;
    //     uint32_t audio_inputs_count; uint32_t audio_outputs_count;
    //     const clap_input_events_t *in_events;
    //     const clap_output_events_t *out_events; }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_process_t}. */
    public static final MemoryLayout CLAP_PROCESS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("steady_time"),
            ValueLayout.JAVA_INT.withName("frames_count"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("transport"),
            ValueLayout.ADDRESS.withName("audio_inputs"),
            ValueLayout.ADDRESS.withName("audio_outputs"),
            ValueLayout.JAVA_INT.withName("audio_inputs_count"),
            ValueLayout.JAVA_INT.withName("audio_outputs_count"),
            ValueLayout.ADDRESS.withName("in_events"),
            ValueLayout.ADDRESS.withName("out_events")
    );

    // -----------------------------------------------------------------------
    // clap_plugin_params_t layout (function pointers):
    //   { uint32_t (*count)(const plugin*);
    //     bool (*get_info)(const plugin*, uint32_t, clap_param_info_t*);
    //     bool (*get_value)(const plugin*, clap_id, double*);
    //     bool (*value_to_text)(const plugin*, clap_id, double, char*, uint32_t);
    //     bool (*text_to_value)(const plugin*, const char*, double*);
    //     void (*flush)(const plugin*, const in_events*, const out_events*); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_params_t}. */
    public static final MemoryLayout CLAP_PLUGIN_PARAMS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("count"),
            ValueLayout.ADDRESS.withName("get_info"),
            ValueLayout.ADDRESS.withName("get_value"),
            ValueLayout.ADDRESS.withName("value_to_text"),
            ValueLayout.ADDRESS.withName("text_to_value"),
            ValueLayout.ADDRESS.withName("flush")
    );

    static final VarHandle PARAMS_COUNT =
            CLAP_PLUGIN_PARAMS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("count"));
    static final VarHandle PARAMS_GET_INFO =
            CLAP_PLUGIN_PARAMS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_info"));
    static final VarHandle PARAMS_GET_VALUE =
            CLAP_PLUGIN_PARAMS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get_value"));

    /** CLAP name size (256 bytes). */
    public static final int CLAP_NAME_SIZE = 256;

    /** CLAP path size (1024 bytes). */
    public static final int CLAP_PATH_SIZE = 1024;

    /**
     * Memory layout for {@code clap_param_info_t}.
     *
     * <pre>{@code
     * { clap_id id; uint32_t flags; void *cookie;
     *   char name[256]; char module[1024];
     *   double min_value; double max_value; double default_value; }
     * }</pre>
     */
    public static final MemoryLayout CLAP_PARAM_INFO_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("id"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.ADDRESS.withName("cookie"),
            MemoryLayout.sequenceLayout(CLAP_NAME_SIZE, ValueLayout.JAVA_BYTE).withName("name"),
            MemoryLayout.sequenceLayout(CLAP_PATH_SIZE, ValueLayout.JAVA_BYTE).withName("module"),
            ValueLayout.JAVA_DOUBLE.withName("min_value"),
            ValueLayout.JAVA_DOUBLE.withName("max_value"),
            ValueLayout.JAVA_DOUBLE.withName("default_value")
    );

    static final VarHandle PARAM_INFO_ID =
            CLAP_PARAM_INFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("id"));
    static final VarHandle PARAM_INFO_MIN =
            CLAP_PARAM_INFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("min_value"));
    static final VarHandle PARAM_INFO_MAX =
            CLAP_PARAM_INFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("max_value"));
    static final VarHandle PARAM_INFO_DEFAULT =
            CLAP_PARAM_INFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("default_value"));

    // -----------------------------------------------------------------------
    // clap_plugin_latency_t layout:
    //   { uint32_t (*get)(const clap_plugin_t *plugin); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_latency_t}. */
    public static final MemoryLayout CLAP_PLUGIN_LATENCY_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("get")
    );

    static final VarHandle LATENCY_GET =
            CLAP_PLUGIN_LATENCY_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("get"));

    // -----------------------------------------------------------------------
    // clap_plugin_state_t layout:
    //   { bool (*save)(const clap_plugin_t*, const clap_ostream_t*);
    //     bool (*load)(const clap_plugin_t*, const clap_istream_t*); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_plugin_state_t}. */
    public static final MemoryLayout CLAP_PLUGIN_STATE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("save"),
            ValueLayout.ADDRESS.withName("load")
    );

    static final VarHandle STATE_SAVE =
            CLAP_PLUGIN_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("save"));
    static final VarHandle STATE_LOAD =
            CLAP_PLUGIN_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("load"));

    // -----------------------------------------------------------------------
    // clap_ostream_t / clap_istream_t layouts
    //   { void *ctx; int64_t (*write/read)(const stream*, const void*, uint64_t); }
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_ostream_t}. */
    public static final MemoryLayout CLAP_OSTREAM_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ctx"),
            ValueLayout.ADDRESS.withName("write")
    );

    /** Memory layout for {@code clap_istream_t}. */
    public static final MemoryLayout CLAP_ISTREAM_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ctx"),
            ValueLayout.ADDRESS.withName("read")
    );

    // -----------------------------------------------------------------------
    // clap_event_header_t / clap_event_param_value_t layouts (CLAP 1.2+)
    // -----------------------------------------------------------------------

    /** Core event space for standard CLAP events. */
    public static final int CLAP_CORE_EVENT_SPACE_ID = 0;

    /** Event type: parameter value change. */
    public static final int CLAP_EVENT_PARAM_VALUE = 5;

    /** Event flag: the change was caused by user interaction (live). */
    public static final int CLAP_EVENT_IS_LIVE = 1;

    /**
     * Memory layout for {@code clap_event_param_value_t} (CLAP 1.2+).
     *
     * <pre>{@code
     * // clap_event_header_t embedded fields:
     * uint32_t size; uint32_t time; uint16_t space_id; uint16_t type; uint32_t flags;
     * // param value fields:
     * clap_id param_id; [padding 4]; void *cookie;
     * int32_t note_id; int16_t port_index; int16_t channel; int16_t key; [padding 6];
     * double value;
     * }</pre>
     */
    public static final MemoryLayout CLAP_EVENT_PARAM_VALUE_LAYOUT = MemoryLayout.structLayout(
            // clap_event_header_t (16 bytes)
            ValueLayout.JAVA_INT.withName("size"),
            ValueLayout.JAVA_INT.withName("time"),
            ValueLayout.JAVA_SHORT.withName("space_id"),
            ValueLayout.JAVA_SHORT.withName("type"),
            ValueLayout.JAVA_INT.withName("flags"),
            // param value payload
            ValueLayout.JAVA_INT.withName("param_id"),
            MemoryLayout.paddingLayout(4),  // align cookie (void*) to 8 bytes
            ValueLayout.ADDRESS.withName("cookie"),
            ValueLayout.JAVA_INT.withName("note_id"),
            ValueLayout.JAVA_SHORT.withName("port_index"),
            ValueLayout.JAVA_SHORT.withName("channel"),
            ValueLayout.JAVA_SHORT.withName("key"),
            MemoryLayout.paddingLayout(6),  // align value (double) to 8 bytes
            ValueLayout.JAVA_DOUBLE.withName("value")
    );

    static final VarHandle PARAM_EVENT_SIZE =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("size"));
    static final VarHandle PARAM_EVENT_SPACE_ID =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("space_id"));
    static final VarHandle PARAM_EVENT_TYPE =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("type"));
    static final VarHandle PARAM_EVENT_FLAGS =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    static final VarHandle PARAM_EVENT_PARAM_ID =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("param_id"));
    static final VarHandle PARAM_EVENT_COOKIE =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("cookie"));
    static final VarHandle PARAM_EVENT_NOTE_ID =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("note_id"));
    static final VarHandle PARAM_EVENT_PORT_INDEX =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("port_index"));
    static final VarHandle PARAM_EVENT_CHANNEL =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("channel"));
    static final VarHandle PARAM_EVENT_KEY =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("key"));
    static final VarHandle PARAM_EVENT_VALUE =
            CLAP_EVENT_PARAM_VALUE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("value"));

    // -----------------------------------------------------------------------
    // clap_input_events_t / clap_output_events_t layouts
    // -----------------------------------------------------------------------

    /** Memory layout for {@code clap_input_events_t}. */
    public static final MemoryLayout CLAP_INPUT_EVENTS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ctx"),
            ValueLayout.ADDRESS.withName("size"),
            ValueLayout.ADDRESS.withName("get")
    );

    /** Memory layout for {@code clap_output_events_t}. */
    public static final MemoryLayout CLAP_OUTPUT_EVENTS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ctx"),
            ValueLayout.ADDRESS.withName("try_push")
    );

    // -----------------------------------------------------------------------
    // Function descriptors for CLAP function pointers
    // -----------------------------------------------------------------------

    /** {@code bool init(const char *plugin_path)} */
    public static final FunctionDescriptor ENTRY_INIT_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS);

    /** {@code void deinit(void)} */
    public static final FunctionDescriptor ENTRY_DEINIT_DESC =
            FunctionDescriptor.ofVoid();

    /** {@code const void *get_factory(const char *factory_id)} */
    public static final FunctionDescriptor ENTRY_GET_FACTORY_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code uint32_t get_plugin_count(const factory*)} */
    public static final FunctionDescriptor FACTORY_GET_PLUGIN_COUNT_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code const clap_plugin_descriptor_t *get_plugin_descriptor(const factory*, uint32_t)} */
    public static final FunctionDescriptor FACTORY_GET_PLUGIN_DESCRIPTOR_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    /** {@code const clap_plugin_t *create_plugin(const factory*, const host*, const char*)} */
    public static final FunctionDescriptor FACTORY_CREATE_PLUGIN_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code bool init(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_INIT_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS);

    /** {@code void destroy(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_DESTROY_DESC =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code bool activate(const clap_plugin_t*, double, uint32_t, uint32_t)} */
    public static final FunctionDescriptor PLUGIN_ACTIVATE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    /** {@code void deactivate(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_DEACTIVATE_DESC =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code bool start_processing(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_START_PROCESSING_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS);

    /** {@code void stop_processing(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_STOP_PROCESSING_DESC =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code void reset(const clap_plugin_t*)} */
    public static final FunctionDescriptor PLUGIN_RESET_DESC =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code clap_process_status process(const clap_plugin_t*, const clap_process_t*)} */
    public static final FunctionDescriptor PLUGIN_PROCESS_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code const void *get_extension(const clap_plugin_t*, const char*)} */
    public static final FunctionDescriptor PLUGIN_GET_EXTENSION_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code uint32_t count(const clap_plugin_t*)} */
    public static final FunctionDescriptor PARAMS_COUNT_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code bool get_info(const clap_plugin_t*, uint32_t, clap_param_info_t*)} */
    public static final FunctionDescriptor PARAMS_GET_INFO_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code bool get_value(const clap_plugin_t*, clap_id, double*)} */
    public static final FunctionDescriptor PARAMS_GET_VALUE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code uint32_t get(const clap_plugin_t*)} */
    public static final FunctionDescriptor LATENCY_GET_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code void request_restart(const clap_host_t*)} */
    public static final FunctionDescriptor HOST_CALLBACK_DESC =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code uint32_t size(const clap_input_events_t*)} */
    public static final FunctionDescriptor EVENTS_SIZE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    /** {@code const clap_event_header_t *get(const clap_input_events_t*, uint32_t)} */
    public static final FunctionDescriptor EVENTS_GET_DESC =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    /** {@code bool try_push(const clap_output_events_t*, const clap_event_header_t*)} */
    public static final FunctionDescriptor EVENTS_TRY_PUSH_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code bool save(const clap_plugin_t*, const clap_ostream_t*)} */
    public static final FunctionDescriptor STATE_SAVE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code bool load(const clap_plugin_t*, const clap_istream_t*)} */
    public static final FunctionDescriptor STATE_LOAD_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /**
     * {@code int64_t write(const clap_ostream_t*, const void* buffer, uint64_t size)}
     * Returns number of bytes written, or -1 on error.
     */
    public static final FunctionDescriptor OSTREAM_WRITE_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

    /**
     * {@code int64_t read(const clap_istream_t*, void* buffer, uint64_t size)}
     * Returns number of bytes read, 0 on end-of-stream, or -1 on error.
     */
    public static final FunctionDescriptor ISTREAM_READ_DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

    private static final Linker LINKER = Linker.nativeLinker();

    private final boolean available;
    private final SymbolLookup lookup;
    private final Arena arena;
    private final MemorySegment entrySegment;

    /**
     * Creates CLAP bindings by loading the shared library at the given path.
     *
     * <p>The path should point to a {@code .clap} plugin bundle (shared library).
     * If the library cannot be loaded, {@link #isAvailable()} returns {@code false}.</p>
     *
     * @param libraryPath path to the CLAP plugin shared library
     */
    public ClapBindings(Path libraryPath) {
        SymbolLookup tempLookup = null;
        MemorySegment tempEntry = MemorySegment.NULL;
        boolean tempAvailable = false;
        Arena tempArena = Arena.ofAuto();

        try {
            tempLookup = SymbolLookup.libraryLookup(libraryPath, tempArena);
            var entryOpt = tempLookup.find("clap_entry");
            if (entryOpt.isPresent()) {
                tempEntry = entryOpt.get().reinterpret(CLAP_PLUGIN_ENTRY_LAYOUT.byteSize());
                tempAvailable = true;
            }
        } catch (IllegalArgumentException | UnsatisfiedLinkError _) {
            // Library not loadable — expected when plugin binary is missing/incompatible
        }

        this.lookup = tempLookup;
        this.arena = tempArena;
        this.entrySegment = tempEntry;
        this.available = tempAvailable;
    }

    /**
     * Returns whether the CLAP library was loaded and the {@code clap_entry}
     * symbol was found.
     *
     * @return true if the library is available
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns the raw {@code clap_entry} memory segment.
     *
     * @return the entry segment
     * @throws ClapException if the library is not available
     */
    public MemorySegment getEntrySegment() {
        requireAvailable();
        return entrySegment;
    }

    /**
     * Creates a downcall method handle for the given function pointer and descriptor.
     *
     * @param functionPointer the native function pointer
     * @param descriptor      the function descriptor
     * @return the method handle
     */
    public static MethodHandle downcallHandle(MemorySegment functionPointer,
                                              FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(functionPointer, descriptor);
    }

    /**
     * Returns the native linker instance.
     *
     * @return the linker
     */
    public static Linker linker() {
        return LINKER;
    }

    /**
     * Reads a null-terminated C string from the given memory segment address.
     *
     * @param address the pointer to the string
     * @return the Java string, or an empty string if the pointer is NULL
     */
    public static String readString(MemorySegment address) {
        if (address.equals(MemorySegment.NULL)) {
            return "";
        }
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Reads a C string from a fixed-size character array within a struct.
     *
     * @param segment the memory segment containing the char array
     * @param offset  the byte offset of the char array within the segment
     * @param maxLen  the maximum length of the char array
     * @return the Java string
     */
    public static String readFixedString(MemorySegment segment, long offset, int maxLen) {
        var slice = segment.asSlice(offset, maxLen);
        var bytes = slice.toArray(ValueLayout.JAVA_BYTE);
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len);
    }

    private void requireAvailable() {
        if (!available) {
            throw new ClapException("CLAP library is not available");
        }
    }
}
