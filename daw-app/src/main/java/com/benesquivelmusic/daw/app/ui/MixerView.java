package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.display.InputMeterStrip;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.PanelGripHandle;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.MeterSinks;
import com.benesquivelmusic.daw.app.ui.metering.VisibleMeterBinding;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.vm.ChannelControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.app.ui.vm.TrackChannelRegistry;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.TrackVM;
import com.benesquivelmusic.daw.app.ui.vm.command.RenameTrackCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleChannelSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.mixer.snapshot.RecallSnapshotAction;
import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.ChannelGrouping;
import com.benesquivelmusic.daw.sdk.audio.ChannelKind;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * A mixer view that displays all project tracks as vertical channel strips.
 *
 * <p>Each track's column contains (from top to bottom):
 * <ul>
 *   <li>VCA member badges, L/R stereo-pair badge, frozen and CPU-degraded
 *       badges (host extras)</li>
 *   <li>Track type icon, input / output routing selectors</li>
 *   <li>Insert effects rack ({@link InsertEffectRack}) — the editing surface —
 *       and the PDC latency label</li>
 *   <li>The story-271 {@link MixerChannelStrip}: I/O captions, the channel's
 *       real insert list (click → Inspector selection), pan knob, fader with
 *       integrated meter, M/S/R toggles and the channel name — beside the
 *       story-137 input meter while the track is armed</li>
 *   <li>The 3D panner button (hidden until a spatial node exists)</li>
 *   <li>Send level controls (one per return bus) and the cue sends</li>
 * </ul>
 *
 * <p>Return buses are displayed as distinct channel strips between the track
 * channels and the master channel, separated by vertical separators.</p>
 *
 * <p>The master channel strip is always displayed on the far right,
 * separated from the return bus channels by a vertical separator.</p>
 *
 * <p>Story 322 (Audio Engine Wiring Design Book §2.10 / §5.6): every strip's
 * fader / pan / mute / solo / arm is a <em>subscriber</em> of the
 * {@link TrackControlWiring}'s view-models and raises its gesture as a
 * {@code TrackCommand} into the wiring's one command sink — the same intent
 * path the arrangement strips drive — so this view writes no
 * {@code MixerChannel} / {@code Track} control state itself. Stereo-link
 * mirroring of those four is the sink's; only the per-return send rows keep
 * a view-side "Link Sends" mirror. Snapshot recall re-seeds the strips
 * structurally, mute / solo / arm styles are seeded from the VM at bind
 * time, and the 3D button is gated on {@code ChannelVM.spatialNodePresent}.</p>
 *
 * <h2>The story-271 skin swap (story 322, slice 5)</h2>
 *
 * <p>The track strips are {@link MixerChannelStrip}s bound through
 * {@link TrackControlBinder#bindStrip}: the strip's name, insert list, pan,
 * dB fader, M/S/R and integrated meter are all VM subscriptions, and its
 * meter is a {@link ChannelVM#bindMeter(Node) ChannelVM-owned} tap-bus
 * subscription rather than a {@link LevelMeterDisplay} registered by this
 * view. The track strips therefore no longer use the legacy
 * {@code .mixer-channel} alias or {@code .mixer-fader} bridge in
 * {@code styles.css}; the return-bus, master and VCA strips (and the
 * hand-built send rows) still do — the remaining story-271 debt.</p>
 *
 * <p>Uses existing CSS classes: {@code .mixer-panel}, {@code .mixer-channel}
 * (return / master / VCA strips), {@code .mixer-channel-name},
 * {@code .mixer-fader} (return / master / VCA / send sliders).</p>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class MixerView extends VBox implements Dockable {

    private static final Logger LOG = Logger.getLogger(MixerView.class.getName());
    /**
     * Story 215 — guard so the "no driver-reported output channels yet,
     * falling back to legacy 0..31 cue-bus pair range" message logs at
     * most once per JVM rather than on every dialog open.
     */
    private static final AtomicBoolean LOGGED_EMPTY_OUTPUT_FALLBACK = new AtomicBoolean(false);

    /**
     * Spinner upper bound (and ComboBox legacy-fallback upper bound) for
     * the cue-bus hardware-output-pair picker when no driver channel
     * info is available — preserves the historical 0..31 range.
     */
    static final int LEGACY_MAX_CUE_BUS_PAIR = 31;

    private static final double FADER_HEIGHT = 150;
    private static final double CHANNEL_WIDTH = 80;
    private static final double METER_WIDTH = 12;
    private static final double METER_HEIGHT = 120;
    private static final double INPUT_METER_WIDTH = 10;
    private static final double CONTROL_ICON_SIZE = 14;
    private static final double SEND_SLIDER_WIDTH = 60;
    /**
     * Story 322 — the solo-safe ring pseudo-class on a strip's solo button
     * (styles.css {@code .track-solo-button:solo-safe}). Package-visible for tests.
     */
    static final PseudoClass SOLO_SAFE = PseudoClass.getPseudoClass("solo-safe");
    /** Node-properties key under which a return / master strip carries its {@link MixerStripControls}. */
    private static final Object STRIP_CONTROLS_KEY = new Object();
    /** Node-properties key under which a track strip's host column carries its {@link TrackStripHandles}. */
    private static final Object TRACK_STRIP_KEY = new Object();
    /**
     * Style class of a track strip's host column (the {@link VBox} holding the
     * {@link MixerChannelStrip} and the host extras). A lookup hook for tests
     * and a future CSS anchor; it carries no rule today. The legacy
     * {@code .mixer-channel} alias is deliberately NOT applied to it (story
     * 322 skin swap).
     */
    static final String TRACK_STRIP_HOST_STYLE_CLASS = "mixer-track-strip";

    private final DawProject project;
    private final UndoManager undoManager;
    private final HBox channelStrips;
    private final HBox returnBusStrips;
    private final HBox cueBusStrips;
    private final HBox vcaStrips;
    private final VBox masterStrip;
    private final List<InsertEffectRack> activeInsertRacks = new ArrayList<>();
    private InsertEffectRack masterInsertRack;
    private java.util.function.BiConsumer<MixerChannel, InsertSlot> onOpenInsertEditor;
    private java.util.function.Consumer<MixerChannel> onChannelSelected;
    private boolean disposed;
    private final List<InputMeterStrip> activeInputMeterStrips = new ArrayList<>();
    /**
     * Story 318 — the output meter of every track / return strip built by the
     * last {@link #refresh()}, paired with the tap point it shows
     * ({@code ChannelPost(channelId)} / {@code ReturnPost(busId)}). Insertion
     * ordered (strip order) and keyed by the display <em>instance</em>, which
     * is also the {@code MeterKey} surface — a strip meter is exactly one
     * subscription. Rebuilt with the strips; the master strip is built once in
     * the constructor and lives in {@link #masterMeterDisplay} instead.
     */
    private final Map<LevelMeterDisplay, MeterTapPoint> stripMeterPoints = new LinkedHashMap<>();
    /** Visibility-owned meter bindings for {@link #stripMeterPoints}; closed by {@link #refresh()}. */
    private final List<VisibleMeterBinding> stripMeterBindings = new ArrayList<>();
    /**
     * Story 322 — a track strip whose integrated meter is fed by its
     * {@link ChannelVM}: {@link TrackControlBinder#bindStrip} subscribed the
     * channel's {@code CHANNEL_POST} tap through {@link ChannelVM#bindMeter(Node)}
     * with the strip as the visibility-owning surface. Recorded so the view can
     * route that VM-owned subscription through the same scene lifecycle as its
     * own {@link LevelMeterDisplay} bindings ({@link #resubscribeAllMeters()} /
     * {@link #disposeStripMeterSubscriptions()}).
     */
    private record TrackStripMeter(ChannelVM channelVm, MixerChannelStrip strip) {

        TrackStripMeter {
            Objects.requireNonNull(channelVm, "channelVm must not be null");
            Objects.requireNonNull(strip, "strip must not be null");
        }
    }

    /** The track strips of the last {@link #refresh()} whose ChannelVM has a live meter feed. */
    private final List<TrackStripMeter> trackStripMeters = new ArrayList<>();
    /**
     * Channel UUIDs (track ids) currently selected via Ctrl/Shift-click on a
     * channel strip. Used to seed the "Create VCA from selection" right-click
     * menu so the engineer can create a VCA over several drum channels in one
     * gesture, matching the issue's "select several channels → right-click →
     * Create VCA" UX.
     */
    private final Set<UUID> selectedChannelIds = new HashSet<>();
    /**
     * Story 135 — pre-mute gain per cue bus. When a cue bus is muted the
     * master gain is set to 0.0 and the previous value is stashed here so
     * unmute restores it correctly even after a {@link #refresh()} rebuilds
     * the strip. Keyed by {@link CueBus#id()}.
     */
    private final Map<UUID, Double> cueBusPreMuteGain = new LinkedHashMap<>();
    /**
     * Story 215 — cue-bus IDs whose persisted {@code hardwareOutputIndex}
     * is no longer present in the live driver. Recomputed from scratch by
     * {@link #validateCueBusesAgainstDevice(NotificationManager)} and
     * consulted by {@link #buildCueBusStrip(CueBus, int)} to render the
     * affected strips with a warning banner while keeping the remove
     * button interactive so the user can still delete or reconfigure.
     */
    private final Set<UUID> disabledCueBusIds = new HashSet<>();
    /**
     * Story 215 — bus IDs for which a stale-output notification has
     * already been emitted. Prevents spamming the same warning on every
     * {@link #refresh()} while still re-notifying when a <em>new</em>
     * bus becomes stale (e.g. after a device change).
     */
    private final Set<UUID> notifiedStaleCueBusIds = new HashSet<>();
    /**
     * Story 215 — optional notification manager wired from the outside.
     * Used by {@link #refresh()} to invoke
     * {@link #validateCueBusesAgainstDevice(NotificationManager)}
     * automatically so that stale cue buses are surfaced on every
     * refresh (project load, device change, etc.).
     */
    private NotificationManager notificationManager;
    /**
     * Story 322 — the live {@link MixerChannel} behind each track strip built
     * by the last {@link #refresh()}, keyed by channel id (= the track's
     * UUID). The "Link Sends" mirror resolves a stereo partner through it.
     * Fader / pan / mute / solo mirroring across a linked pair is no longer a
     * widget-map concern of this view: the wiring's
     * {@code LinkedTrackCommandDispatcher} writes the partner's model and the
     * partner's controls follow as VM subscribers.
     */
    private final Map<UUID, MixerChannel> channelByChannelId = new LinkedHashMap<>();
    /**
     * Story 322 — every per-return send row of the last {@link #refresh()},
     * keyed by channel id then by target return bus (identity), so a send edit
     * on one member of a stereo pair can reflect the mirrored <em>model</em>
     * send on the partner's row (slider + tap glyph) without a strip rebuild.
     */
    private final Map<UUID, Map<MixerChannel, SendRow>> sendRowsByChannelId = new LinkedHashMap<>();
    /**
     * Story 322 — the disposers of every VM binding of the last
     * {@link #refresh()}: one {@link TrackControlBinder} per track strip, one
     * {@link ChannelControlBinder} per return strip and one for the master,
     * plus the per-strip listeners (3D-button gating, the story-137 arm
     * refresh). Run at the top of {@link #refresh()} and in {@link #dispose()}
     * so no listener outlives its strip ({@code javafx-application-design}
     * §4 / §15).
     */
    private final List<Runnable> stripBindingDisposers = new ArrayList<>();
    /**
     * The master strip's controls — built once in the constructor, re-bound
     * against the current wiring generation on every {@link #refresh()}.
     */
    private MixerStripControls masterStripControls;
    /**
     * Listener registered on the project's {@link ChannelLinkManager} that
     * triggers a {@link #refresh()} whenever a link is added, removed, or
     * replaced — keeps the chain glyphs, connector lines, and L/R badges
     * in sync with the model. Held as a field so we can deregister if the
     * view is replaced or disposed.
     */
    private final Runnable channelLinkListener;
    /**
     * Side panel listing saved mixer-scene snapshots (Story 103). Mounted
     * to the right of the channel strips; toggled via the "Snapshots"
     * toolbar button and the corresponding mixer-menu item.
     */
    private final MixerSnapshotsPanel snapshotsPanel;
    /** Top-row button that toggles {@link #snapshotsPanel} visibility. */
    private final ToggleButton snapshotsToggleButton;
    /** Top-row "A" slot button — recalls or saves to {@link MixerSnapshotManager.Slot#A}. */
    private final Button slotAButton;
    /** Top-row "B" slot button — recalls or saves to {@link MixerSnapshotManager.Slot#B}. */
    private final Button slotBButton;
    /** Container that holds {@link #snapshotsPanel} when visible. */
    private HBox mainArea;
    /**
     * Callbacks that re-sync solo-button visuals and the "Solo safe"
     * {@code CheckMenuItem} from the model. Invoked after undo/redo so
     * solo-safe changes round-trip through the UI even when the user did
     * not initiate them via the context menu.
     */
    private final List<Runnable> soloSafeSyncCallbacks = new ArrayList<>();
    /**
     * History listener registered on the {@link UndoManager}. Runs every
     * {@link #soloSafeSyncCallbacks} entry on the JavaFX thread so the solo
     * ring and "Solo safe" checkmark always reflect the model after
     * undo/redo (or any other history mutation).
     */
    private final com.benesquivelmusic.daw.core.undo.UndoHistoryListener undoHistoryListener;
    private PluginRegistry pluginRegistry;
    /**
     * Shared {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor}
     * propagated to every {@link InsertEffectRack} created by this view so
     * plugin reorder-drag gestures get the unified visual feedback layer
     * (story 197).
     */
    private com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor dragVisualAdvisor;
    private InputLevelMonitorRegistry inputLevelMonitorRegistry;
    /**
     * Story 318 — the FX-pulse drain every strip meter subscribes through
     * (Audio Engine Wiring Design Book §4.3). {@code null} until
     * {@link #setMeterFeed(MeterFeed)} is called (and in every pure-unit
     * context), in which case the strips render at their −120 dB floor exactly
     * as they did before the tap bus existed.
     */
    private MeterFeed meterFeed;
    /**
     * Story 322 — the host's live per-project-generation control wiring
     * (VM registry + command sink), handed in by the four-argument constructor
     * at both production sites (so the constructor's own first
     * {@link #refresh()} already binds through it) or injected later via
     * {@link #setTrackControlWiring(Supplier)}. {@code null} in every
     * pure-unit context.
     */
    private Supplier<TrackControlWiring> trackControlWiringSupplier;
    /**
     * Story 322 — the wiring this view lazily builds and owns when no host
     * wiring was injected (or the supplier yields {@code null}), so the
     * {@code new MixerView(project)} tests bind exactly like production.
     * Disposed in {@link #dispose()} and when a host wiring arrives.
     */
    private TrackControlWiring standaloneTrackControlWiring;
    /**
     * How many standalone wirings this view has built for itself — the probe
     * behind {@link #standaloneTrackControlWiringBuilds()}: a hosted view
     * (four-argument constructor) must never build one (story 322 fix round,
     * N1 — each build is a full registry, its VMs and three continuous
     * channels per channel, then a second strip build to replace it).
     */
    private int standaloneTrackControlWiringBuilds;
    /** The dispatcher {@link #standaloneTrackControlWiring} was built over when none was reachable. */
    private FxDispatcher standaloneDispatcher;
    /** The master strip's output meter — built once in the constructor, never rebuilt. */
    private LevelMeterDisplay masterMeterDisplay;
    /** The master strip's {@code MASTER_OUT} binding; survives strip refreshes. */
    private VisibleMeterBinding masterMeterBinding;
    /**
     * Story 318 — {@code true} before the first scene attachment and while detached.
     * While it is set this view holds NO meter subscription, so a
     * {@link #refresh()} driven from elsewhere (a track added while the Mixer
     * is off screen) cannot re-acquire live tokens on a view that may never be
     * shown again — which would pin the view, its displays and the old
     * project's channels in the app-scoped feed for the life of the process.
     */
    private boolean meterSceneDetached = true;
    /**
     * Story 318 — {@code true} while the channel-link and undo-history
     * listeners are registered. The scene-detach branch removes them; the
     * re-attach branch has to put them back, because the
     * {@code ViewNavigationController} re-mounts the SAME instance on every
     * return to the Mixer.
     */
    private boolean modelListenersAttached;
    private java.util.function.Supplier<List<AudioChannelInfo>> inputChannelInfoSupplier =
            () -> List.of();
    private java.util.function.Supplier<List<AudioChannelInfo>> outputChannelInfoSupplier =
            () -> List.of();
    /**
     * Story 100 — track templates and channel-strip presets. When set, the
     * per-channel right-click menu grows "Save channel strip\u2026" and
     * "Apply channel strip\u2026" entries that delegate to the controller.
     * {@code null} hides the entries.
     */
    private TrackTemplateController trackTemplateController;
    /**
     * Story 035 — When wired, the per-channel right-click menu grows
     * "Freeze track" / "Unfreeze track" entries and the channel-name
     * area shows a small ❄ snowflake glyph for frozen tracks. The
     * controller also provides the cache-state tooltip.
     */
    private TrackFreezeController trackFreezeController;
    /**
     * Story 129 (UI) — Predicate that returns {@code true} when the
     * channel currently associated with the given track id is in a
     * "degraded" state (i.e. the per-track CPU budget enforcer has
     * tripped its budget). When non-{@code null}, every degraded
     * strip renders a small "⚠" badge under the channel name.
     */
    private java.util.function.Predicate<String> degradedTrackPredicate = _ -> false;
    /**
     * Story 129 (UI) — Optional handler invoked when the user picks
     * "CPU Budget…" from the per-channel context menu. Receives the
     * mixer channel; null hides the menu entry.
     */
    private java.util.function.Consumer<MixerChannel> onConfigureCpuBudget;
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path and threaded into every {@link InsertEffectRack} this view builds.
     * May be {@code null} in a pure-unit context (the compatibility
     * constructors default it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Creates a new mixer view bound to the given project.
     *
     * @param project the DAW project to visualize
     */
    public MixerView(DawProject project) {
        this(project, null);
    }

    /**
     * Creates a new mixer view bound to the given project with undo support.
     *
     * @param project     the DAW project to visualize
     * @param undoManager the undo manager for insert effect operations (may be {@code null})
     */
    public MixerView(DawProject project, UndoManager undoManager) {
        this(project, undoManager, FxDispatcher.getDefault());
    }

    /**
     * Creates a new mixer view bound to the given project, with undo support
     * and an explicit FX-thread marshalling seam (story 289). The view binds
     * its strips through a standalone wiring it builds for itself (the
     * pure-unit fallback) until {@link #setTrackControlWiring(Supplier)}.
     *
     * @param project      the DAW project to visualize
     * @param undoManager  the undo manager for insert effect operations (may be {@code null})
     * @param fxDispatcher the FX-thread marshalling seam, or {@code null} to use
     *                     the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public MixerView(DawProject project, UndoManager undoManager, FxDispatcher fxDispatcher) {
        this(project, undoManager, fxDispatcher, null);
    }

    /**
     * Creates a new mixer view bound to the given project, with undo support,
     * an explicit FX-thread marshalling seam (story 289) and the host's live
     * {@link TrackControlWiring} supplier (story 322, Audio Engine Wiring
     * Design Book §2.10 / §5.6), so the constructor's own first
     * {@link #refresh()} already binds every strip through the host's
     * per-project generation and a hosted view never builds a standalone
     * wiring for itself. (Story 322 fix round, N1: construct-then-
     * {@code setTrackControlWiring} built a full registry, its VMs and their
     * continuous channels, disposed them and rebuilt every strip — twice per
     * project load.) Both production sites use this constructor; the shorter
     * ones keep the lazy standalone fallback for tests. The host must have
     * built the generation for {@code project} before calling this: the
     * supplier is resolved during construction.
     *
     * @param project            the DAW project to visualize
     * @param undoManager        the undo manager for insert effect operations (may be {@code null})
     * @param fxDispatcher       the FX-thread marshalling seam, or {@code null} to use
     *                           the {@link FxDispatcher#getDefault() app-scoped default}
     * @param trackControlWiring the host's live wiring supplier, resolved on every
     *                           use and never captured (the host swaps the wiring
     *                           per project load); {@code null} — or a supplier that
     *                           yields {@code null} — leaves the view to its own
     *                           standalone wiring
     */
    public MixerView(DawProject project, UndoManager undoManager, FxDispatcher fxDispatcher,
                     Supplier<TrackControlWiring> trackControlWiring) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = undoManager;
        // May be null in a pure-unit context; postFx() / InsertEffectRack fall
        // back to the static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
        // Set BEFORE the refresh() at the end of this constructor, which is
        // what binds the strips through it.
        this.trackControlWiringSupplier = trackControlWiring;
        getStyleClass().add("mixer-panel");

        // Refresh solo-safe button rings and "Solo safe" checkmarks after
        // any undo/redo so the UI never shows a stale value when
        // SetSoloSafeAction (or a snapshot recall, etc.) is undone or
        // redone. The listener runs on whatever thread fired the event;
        // hop to the FX application thread to mutate widgets safely.
        this.undoHistoryListener = _ -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                onUndoHistoryChanged();
            } else {
                postFx(this::onUndoHistoryChanged);
            }
        };
        if (this.undoManager != null) {
            this.undoManager.addHistoryListener(this.undoHistoryListener);
        }

        // ── Mixer channel-link manager hook (Story 159) ─────────────────────
        // Re-render whenever a stereo pair is created, removed, or re-edited
        // via the popover so the chain glyphs, connector lines, and L/R
        // badges always reflect the model. Hop to the FX thread because
        // ChannelLinkManager fires synchronously on the mutating thread.
        this.channelLinkListener = () -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                refresh();
            } else {
                postFx(this::refresh);
            }
        };
        project.getChannelLinkManager().addListener(this.channelLinkListener);
        this.modelListenersAttached = true;

        // Auto-unregister listeners when this view is removed from a scene
        // so a replaced MixerView (e.g. on project reload via
        // ViewNavigationController.setMixerView) does not stay strongly
        // referenced by ChannelLinkManager or UndoManager (memory-leak fix).
        sceneProperty().addListener((_, _, newScene) -> {
            if (disposed) return;
            if (newScene == null) {
                releaseModelListeners();
                // Story 318 — a detached MixerView keeps no meter subscription
                // alive in the app-scoped MeterFeed (javafx-application-design
                // §10/§15). The feed reference is kept so the re-attach branch
                // below can subscribe again.
                meterSceneDetached = true;
                disposeAllMeterSubscriptions();
            } else {
                // Re-mounted: the ViewNavigationController caches this view and
                // puts the SAME instance back into the BorderPane's centre on
                // every return to the Mixer, and no host calls setMeterFeed
                // again — so EVERY resource the detach branch released has to
                // be re-acquired here or it is gone for the rest of the
                // session: the meters would stay dark, undo/redo would stop
                // refreshing the solo-safe rings, and a channel-link edit would
                // stop re-rendering the strips.
                // Story 322 fix round 1 (S3): catch up on whatever the history
                // did while this view was off-screen. The listeners were
                // released on detach, so an undo / redo made from another view
                // of a channel-only action (a raw snapshot recall, a solo-safe
                // edit, a send action) moved the channels without healing the
                // Track mirrors or re-seeding the solo-safe rings and send
                // rows. Same work as one history event; when nothing changed
                // every command is a VALIDATE no-op. Only after a real detach:
                // a view constructed attached and mounted for the first time
                // has missed nothing (and a test sink recording its commands
                // must not see a phantom heal).
                boolean missedHistory = !modelListenersAttached;
                acquireModelListeners();
                if (missedHistory) {
                    onUndoHistoryChanged();
                }
                meterSceneDetached = false;
                resubscribeAllMeters();
            }
        });

        Label header = new Label("MIXER");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.MIXER, 16));
        header.setPadding(new Insets(0, 0, 6, 0));

        // ── Snapshots panel & A/B controls (Story 103) ──────────────────────
        // The panel is constructed against the project's persistent
        // MixerSnapshotManager so saved scenes round-trip through
        // ProjectSerializer. The MixerView never replaces the manager —
        // it always reads project.getMixerSnapshotManager() so a freshly
        // loaded project's snapshots appear automatically.
        this.snapshotsPanel = new MixerSnapshotsPanel(
                project.getMixerSnapshotManager(), project.getMixer(), undoManager);
        // Story 322 — the panel's own recall writes channels only; heal the
        // Track mirrors after it exactly as the A/B recall does.
        this.snapshotsPanel.setOnChange(() -> {
            syncSlotButtons();
            healTrackMirrorsFromChannels();
        });

        this.snapshotsToggleButton = new ToggleButton("Snapshots");
        this.snapshotsToggleButton.setTooltip(new Tooltip(
                "Show or hide the mixer scene snapshots panel."));
        this.snapshotsToggleButton.selectedProperty().addListener((_, _, selected) -> {
            if (selected) {
                if (!mainAreaContains(snapshotsPanel)) {
                    mainArea.getChildren().add(snapshotsPanel);
                }
            } else {
                mainArea.getChildren().remove(snapshotsPanel);
            }
        });

        this.slotAButton = new Button("A");
        this.slotAButton.setTooltip(new Tooltip(
                "Recall snapshot slot A. Right-click to save the current state to A."));
        this.slotAButton.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.A));
        this.slotAButton.setContextMenu(buildSlotContextMenu(MixerSnapshotManager.Slot.A));

        this.slotBButton = new Button("B");
        this.slotBButton.setTooltip(new Tooltip(
                "Recall snapshot slot B. Right-click to save the current state to B."));
        this.slotBButton.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.B));
        this.slotBButton.setContextMenu(buildSlotContextMenu(MixerSnapshotManager.Slot.B));

        // Mixer maintenance menu — exposes "Reset solo safe to defaults"
        // (legacy) plus the Story 103 Snapshots submenu.
        MenuButton mixerMenu = new MenuButton("⋮");
        mixerMenu.setTooltip(new Tooltip("Mixer options"));
        MenuItem resetSoloSafeItem = new MenuItem("Reset solo safe to defaults");
        resetSoloSafeItem.setOnAction(_ -> {
            project.getMixer().resetSoloSafeToDefaults();
            refresh();
        });

        // Snapshots submenu — Save / Manage / Recall A / Recall B
        Menu snapshotsMenu = new Menu("Snapshots");
        MenuItem saveSnapshotItem = new MenuItem("Save current state…");
        saveSnapshotItem.setOnAction(_ -> snapshotsPanel.getSaveButton().fire());
        MenuItem manageSnapshotsItem = new MenuItem("Manage…");
        manageSnapshotsItem.setOnAction(_ -> snapshotsToggleButton.setSelected(true));
        MenuItem recallAItem = new MenuItem("Recall A");
        recallAItem.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.A));
        MenuItem recallBItem = new MenuItem("Recall B");
        recallBItem.setOnAction(_ -> recallSlot(MixerSnapshotManager.Slot.B));
        snapshotsMenu.getItems().addAll(
                saveSnapshotItem, manageSnapshotsItem, recallAItem, recallBItem);

        mixerMenu.getItems().addAll(resetSoloSafeItem, snapshotsMenu);

        // Story 135 — "New cue bus…" entry. Keeps the discovery affordance
        // for cue mixes consistent with VCA / return-bus creation flows
        // (Mixer menu) while also being available as a "+" button on the
        // cue strip area itself.
        MenuItem newCueBusItem = new MenuItem("New cue bus\u2026");
        newCueBusItem.setOnAction(_ -> promptCreateCueBus());
        mixerMenu.getItems().add(newCueBusItem);

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        // Story 288 — dock grip leads the header. Its own drag gesture
        // consumes, so it never collides with the per-channel-strip
        // (TransferMode.LINK) drag wired in buildChannelStrip(...).
        HBox headerRow = new HBox(8,
                new PanelGripHandle(dockId(), this),
                header, toolbarSpacer,
                slotAButton, slotBButton,
                snapshotsToggleButton, mixerMenu);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(0, 0, 6, 0));

        channelStrips = new HBox(6);
        channelStrips.setAlignment(Pos.TOP_LEFT);

        returnBusStrips = new HBox(6);
        returnBusStrips.setAlignment(Pos.TOP_LEFT);

        // Story 135 — "Cue Mix Bus" strips. Sit between the return buses and
        // the master so engineers see them next to the headphone routing
        // they care about during tracking.
        cueBusStrips = new HBox(6);
        cueBusStrips.setAlignment(Pos.TOP_LEFT);

        vcaStrips = new HBox(6);
        vcaStrips.setAlignment(Pos.TOP_LEFT);

        masterStrip = buildMasterStrip();

        HBox allStrips = new HBox(6);
        allStrips.setAlignment(Pos.TOP_LEFT);
        allStrips.getChildren().addAll(
                channelStrips,
                new Separator(Orientation.VERTICAL),
                returnBusStrips,
                new Separator(Orientation.VERTICAL),
                cueBusStrips,
                new Separator(Orientation.VERTICAL),
                masterStrip,
                new Separator(Orientation.VERTICAL),
                vcaStrips);
        HBox.setHgrow(channelStrips, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(allStrips);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        // mainArea wraps the strip area and the (optional) snapshots side panel
        // so toggling the panel is an O(1) add/remove of a child node.
        mainArea = new HBox(0, scrollPane);
        HBox.setHgrow(scrollPane, Priority.ALWAYS);
        VBox.setVgrow(mainArea, Priority.ALWAYS);

        getChildren().addAll(headerRow, mainArea);
        setPadding(new Insets(8));

        syncSlotButtons();

        // Keep slot button highlights in sync after undo/redo of saves/
        // recalls so the "active" slot indicator never goes stale.
        if (this.undoManager != null) {
            this.undoManager.addHistoryListener(_ -> {
                if (javafx.application.Platform.isFxApplicationThread()) {
                    snapshotsPanel.refresh();
                    syncSlotButtons();
                } else {
                    postFx(() -> {
                        snapshotsPanel.refresh();
                        syncSlotButtons();
                    });
                }
            });
        }

        refresh();
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289). Callers
     * already guard with {@code Platform.isFxApplicationThread()} where an
     * inline run is wanted; this is only the off-thread hop.
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    // ── Dockable contract (story 285) ────────────────────────────────────────
    @Override public String dockId()            { return DefaultWorkspaces.PANEL_MIXER; }
    @Override public String displayName()       { return "Mixer"; }
    @Override public String iconName()          { return "MIXER"; }
    @Override public DockZone preferredZone()   { return DockZone.BOTTOM; }

    private boolean mainAreaContains(javafx.scene.Node node) {
        return mainArea != null && mainArea.getChildren().contains(node);
    }

    private ContextMenu buildSlotContextMenu(MixerSnapshotManager.Slot slot) {
        ContextMenu menu = new ContextMenu();
        MenuItem saveItem = new MenuItem("Save current state to " + slot.name());
        saveItem.setOnAction(_ -> saveCurrentStateToSlot(slot));
        MenuItem clearItem = new MenuItem("Clear " + slot.name());
        clearItem.setOnAction(_ -> {
            project.getMixerSnapshotManager().setSlot(slot, null);
            syncSlotButtons();
        });
        menu.getItems().addAll(saveItem, clearItem);
        return menu;
    }

    private void saveCurrentStateToSlot(MixerSnapshotManager.Slot slot) {
        MixerSnapshot snap = MixerSnapshot.capture(
                project.getMixer(), "Slot " + slot.name());
        project.getMixerSnapshotManager().setSlot(slot, snap);
        syncSlotButtons();
    }

    private void recallSlot(MixerSnapshotManager.Slot slot) {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        MixerSnapshot snap = manager.getSlot(slot);
        if (snap == null) {
            return;
        }
        MixerSnapshotManager.Slot previousSlot = manager.getActiveSlot();
        RecallSnapshotAction recallAction = new RecallSnapshotAction(project.getMixer(), snap);
        UndoableAction compound = new UndoableAction() {
            @Override public String description() { return "Recall Mixer Snapshot (" + slot.name() + ")"; }
            @Override public void execute() {
                manager.setActiveSlot(slot);
                recallAction.execute();
                healTrackMirrorsFromChannels();
            }
            @Override public void undo() {
                recallAction.undo();
                healTrackMirrorsFromChannels();
                manager.setActiveSlot(previousSlot);
            }
        };
        if (undoManager != null) {
            undoManager.execute(compound);
        } else {
            compound.execute();
        }
        syncSlotButtons();
    }

    /**
     * Toggles between the A and B snapshot slots, applying the newly active
     * slot's state to the mixer as a single compound undoable action. Bound
     * to {@link DawAction#MIXER_TOGGLE_AB} (default {@code Shift+A}).
     */
    public void toggleAB() {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        MixerSnapshotManager.Slot previous = manager.getActiveSlot();
        MixerSnapshotManager.Slot next =
                (previous == MixerSnapshotManager.Slot.A)
                        ? MixerSnapshotManager.Slot.B
                        : MixerSnapshotManager.Slot.A;
        MixerSnapshot snap = manager.getSlot(next);
        if (snap != null) {
            RecallSnapshotAction recallAction = new RecallSnapshotAction(project.getMixer(), snap);
            UndoableAction compound = new UndoableAction() {
                @Override public String description() { return "Toggle Mixer A/B"; }
                @Override public void execute() {
                    manager.setActiveSlot(next);
                    recallAction.execute();
                    healTrackMirrorsFromChannels();
                }
                @Override public void undo() {
                    recallAction.undo();
                    healTrackMirrorsFromChannels();
                    manager.setActiveSlot(previous);
                }
            };
            if (undoManager != null) {
                undoManager.execute(compound);
            } else {
                compound.execute();
            }
        } else {
            manager.setActiveSlot(next);
        }
        syncSlotButtons();
    }

    private void syncSlotButtons() {
        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        boolean activeA = manager.getActiveSlot() == MixerSnapshotManager.Slot.A;
        applySlotButtonStyle(slotAButton, activeA, manager.getSlot(MixerSnapshotManager.Slot.A) != null);
        applySlotButtonStyle(slotBButton, !activeA, manager.getSlot(MixerSnapshotManager.Slot.B) != null);
    }

    private static void applySlotButtonStyle(Button btn, boolean active, boolean filled) {
        btn.getStyleClass().removeAll("mixer-slot-active", "mixer-slot-filled");
        if (active) {
            btn.getStyleClass().add("mixer-slot-active");
        } else if (filled) {
            btn.getStyleClass().add("mixer-slot-filled");
        }
    }

    /** Returns the snapshots side panel. Visible for testing. */
    public MixerSnapshotsPanel getSnapshotsPanel() {
        return snapshotsPanel;
    }

    /** Returns the snapshots toggle button. Visible for testing. */
    ToggleButton getSnapshotsToggleButton() {
        return snapshotsToggleButton;
    }

    /** Returns the A-slot toolbar button. Visible for testing. */
    Button getSlotAButton() {
        return slotAButton;
    }

    /** Returns the B-slot toolbar button. Visible for testing. */
    Button getSlotBButton() {
        return slotBButton;
    }

    /**
     * Sets the plugin registry for this mixer view. When set, all insert
     * effect racks will offer registered external plugins as additional
     * insert options.
     *
     * @param registry the plugin registry, or {@code null} to disable
     */
    public void setPluginRegistry(PluginRegistry registry) {
        this.pluginRegistry = registry;
        for (InsertEffectRack rack : activeInsertRacks) {
            rack.setPluginRegistry(registry);
        }
    }

    /**
     * Installs the shared {@link com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor}
     * for plugin reorder-drag visual feedback (ghost preview, drop-zone
     * highlight, modifier cursor) — see user story 197. Propagates the
     * advisor to all currently-active and future {@link InsertEffectRack}
     * instances.
     *
     * @param advisor the shared advisor, or {@code null} to disable
     */
    public void setDragVisualAdvisor(
            com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor advisor) {
        this.dragVisualAdvisor = advisor;
        for (InsertEffectRack rack : activeInsertRacks) {
            rack.setDragVisualAdvisor(advisor);
        }
    }

    /**
     * Wires the {@link TrackTemplateController} that powers the per-channel
     * "Save channel strip\u2026" and "Apply channel strip\u2026" right-click
     * actions (Story 100). Pass {@code null} to suppress those entries.
     *
     * @param controller the controller, or {@code null} to disable
     */
    public void setTrackTemplateController(TrackTemplateController controller) {
        this.trackTemplateController = controller;
    }

    /**
     * Wires the {@link TrackFreezeController} that powers the per-channel
     * "Freeze track" / "Unfreeze track" right-click entries and the
     * inline ❄ snowflake glyph next to the channel name (Story 035).
     * Pass {@code null} to suppress both the context-menu entries and
     * the snowflake badge. {@link #refresh()} must be called after
     * binding so the strips are rebuilt with the new state.
     */
    public void setTrackFreezeController(TrackFreezeController controller) {
        this.trackFreezeController = controller;
    }

    /**
     * Story 129 (UI) — Wires a predicate that decides whether a
     * given track id should render the "⚠" CPU-degraded badge under
     * its channel name. Pass {@code null} to disable the badge.
     * Call {@link #refresh()} (or invoke from the binding's UI
     * callback) to re-render strips after the predicate's result
     * changes.
     *
     * @param predicate test invoked per channel strip rebuild; may be
     *                  {@code null}
     */
    public void setDegradedTrackPredicate(java.util.function.Predicate<String> predicate) {
        this.degradedTrackPredicate = (predicate != null) ? predicate : _ -> false;
    }

    /**
     * Story 129 (UI) — Wires the "CPU Budget…" entry on the per-
     * channel right-click menu. The handler receives the
     * {@link MixerChannel} for the strip; pass {@code null} to hide
     * the menu entry.
     *
     * @param handler invoked when the user picks the entry
     */
    public void setOnConfigureCpuBudget(java.util.function.Consumer<MixerChannel> handler) {
        this.onConfigureCpuBudget = handler;
    }

    /**
     * Binds an {@link InputLevelMonitorRegistry} so that armed tracks show
     * an input-signal meter column with a latching clip LED (user story 137).
     *
     * <p>When set, every channel strip whose backing track is armed gets a
     * second vertical meter column sourced from the track's
     * {@link InputLevelMonitor}. Clicking the clip LED on any strip resets
     * that track's latch; {@code Alt+click} resets every track's latch via
     * {@link InputLevelMonitorRegistry#resetAll()}.</p>
     *
     * <p>Call {@link #refresh()} after binding (or rebinding) so the strips
     * rebuild with the new registry.</p>
     *
     * @param registry the registry to bind, or {@code null} to disable the
     *                 input-meter column
     */
    public void setInputLevelMonitorRegistry(InputLevelMonitorRegistry registry) {
        this.inputLevelMonitorRegistry = registry;
    }

    /**
     * Returns the currently bound input-level monitor registry, or
     * {@code null} if none has been set.
     */
    public InputLevelMonitorRegistry getInputLevelMonitorRegistry() {
        return inputLevelMonitorRegistry;
    }

    // ── Story 318 — output meters fed by the metering tap bus ────────────────

    /**
     * Binds the app-scoped {@link MeterFeed} so every strip's output meter
     * shows the level the engine actually renders (story 318; Audio Engine
     * Wiring Design Book §4.3, §5.3).
     *
     * <p>Each track strip subscribes {@link MeterTapPoint.ChannelPost} for its
     * channel id, each return strip {@link MeterTapPoint.ReturnPost} for its
     * bus id, and the master strip {@link MeterTapPoint#MASTER_OUT} — always
     * <em>post-fader</em>, so a fader move is visible on the meter. The
     * {@code MeterKey} surface is the {@link LevelMeterDisplay} instance, so a
     * strip can never accumulate two subscriptions for the same meter, and the
     * {@link VisibleMeterBinding} acquires a subscription only while the
     * display and its ancestors are visible in a showing window. Hidden or
     * unmounted meters leave no entry in the FX-pulse drain. Meters fall to their
     * −120 dB floor at stop through the feed's silent frame ("honest idle") —
     * this view runs no timer and holds no synthetic feed.</p>
     *
     * <p>Call it any time: the strips built before the feed arrived are
     * subscribed here, and every later {@link #refresh()} re-subscribes the
     * strips it rebuilds. Passing a different feed replaces every
     * subscription; passing {@code null} (or a disposed feed) disposes them,
     * after which the displays keep whatever level they last showed and
     * decay through their own ballistics — an unsubscribed meter is not
     * reset, it simply stops being told anything.</p>
     *
     * <p>Story 322 — the track strips meter through their {@link ChannelVM},
     * whose feed is fixed at the VM's construction. The host's wiring is built
     * over the same app-scoped feed this method receives; a standalone wiring
     * this view built for itself before the feed arrived is disposed and the
     * strips rebuilt over one that carries it.</p>
     *
     * @param feed the FX-pulse meter drain, or {@code null} to unsubscribe
     */
    public void setMeterFeed(MeterFeed feed) {
        this.meterFeed = feed;
        activeInsertRacks.forEach(rack -> rack.setMeterFeed(feed));
        if (standaloneTrackControlWiring != null && !disposed) {
            // The strip binders go first — they subscribe the VMs the
            // standalone wiring owns (the dispose() order).
            disposeStripBindings();
            disposeStandaloneTrackControlWiring();
            refresh();
        }
        resubscribeAllMeters();
    }

    /**
     * Disposes every meter subscription this view owns and — when a usable
     * feed is bound — creates them again from the retained
     * {@link #stripMeterPoints} plus the master display. This is the single
     * subscription pass: {@link #setMeterFeed(MeterFeed)} runs it on a feed
     * swap and the scene listener runs it when the view is re-mounted, so a
     * MixerView that the {@code ViewNavigationController} cached and put back
     * on screen meters again instead of staying dark
     * ({@code javafx-application-design} §10/§15 — release on detach, but
     * re-acquire on re-attach).
     */
    private void resubscribeAllMeters() {
        disposeAllMeterSubscriptions();
        if (meterSceneDetached || !meterFeedUsable()) {
            return;
        }
        if (masterMeterDisplay != null) {
            masterMeterBinding = bindMeter(masterMeterDisplay, MeterTapPoint.MASTER_OUT);
        }
        for (Map.Entry<LevelMeterDisplay, MeterTapPoint> entry : stripMeterPoints.entrySet()) {
            stripMeterBindings.add(bindMeter(entry.getKey(), entry.getValue()));
        }
        // Story 322 — the track strips' VM-owned subscriptions re-acquire on the
        // same gate. A VM whose feed has since been disposed leaves its strip
        // at the floor, exactly like a LevelMeterDisplay without a usable feed.
        for (TrackStripMeter meter : trackStripMeters) {
            if (meter.channelVm().hasMeterFeed()) {
                meter.channelVm().bindMeter(meter.strip());
            }
        }
    }

    public void setOnOpenInsertEditor(java.util.function.BiConsumer<MixerChannel, InsertSlot> handler) {
        onOpenInsertEditor = handler;
        activeInsertRacks.forEach(rack -> rack.setOnOpenEditor(handler));
    }

    public void setOnChannelSelected(java.util.function.Consumer<MixerChannel> handler) {
        onChannelSelected = handler;
    }

    public void showInsertPicker(MixerChannel channel) {
        if (disposed) return;
        activeInsertRacks.stream().filter(rack -> rack.getChannel() == channel).findFirst()
                .ifPresent(rack -> rack.showEffectPicker(channel.getInsertCount()));
    }

    /** Retires this project view, including pending rack loads and editor windows. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        releaseModelListeners();
        disposeAllMeterSubscriptions();
        activeInsertRacks.forEach(InsertEffectRack::dispose);
        activeInsertRacks.clear();
        activeInputMeterStrips.forEach(InputMeterStrip::stop);
        activeInputMeterStrips.clear();
        // Story 322 — the strip binders go first (they subscribe the VMs the
        // standalone wiring below would dispose); only a wiring this view
        // built for itself is its to dispose — the host's generation outlives
        // the view.
        disposeStripBindings();
        disposeStandaloneTrackControlWiring();
        onOpenInsertEditor = null;
        onChannelSelected = null;
        meterFeed = null;
    }

    public void refreshInsertRack(MixerChannel channel) {
        activeInsertRacks.stream().filter(rack -> rack.getChannel() == channel)
                .forEach(InsertEffectRack::rebuildSlots);
    }

    /** Returns the currently bound {@link MeterFeed}, or {@code null}. Visible for testing. */
    MeterFeed getMeterFeed() {
        return meterFeed;
    }

    /**
     * Story 322 — injects the host's live {@link TrackControlWiring} supplier
     * (Audio Engine Wiring Design Book §2.10 / §5.6 "one intent path, both
     * surfaces") into a view built without one; the production sites hand it
     * to the four-argument constructor instead. The supplier is resolved on
     * every use, never captured, because
     * {@code MainController.rebuildTrackControlWiring()} swaps the wiring per
     * project load. A standalone wiring this view had already built for itself
     * is disposed and the strips rebuilt against the host's.
     *
     * @param supplier the live wiring supplier; must not be {@code null} (it may
     *                 <em>yield</em> {@code null}, in which case the view falls
     *                 back to its own standalone wiring)
     */
    public void setTrackControlWiring(Supplier<TrackControlWiring> supplier) {
        this.trackControlWiringSupplier = Objects.requireNonNull(supplier, "supplier must not be null");
        if (standaloneTrackControlWiring != null) {
            // The strip binders go first — they subscribe the VMs the
            // standalone wiring owns (the dispose() order).
            disposeStripBindings();
            disposeStandaloneTrackControlWiring();
            refresh();
        }
    }

    /**
     * How many standalone wirings this view has built for itself so far —
     * zero for a view constructed with the host's supplier. Package-visible
     * for tests (story 322 fix round, N1).
     *
     * @return the standalone-wiring build count
     */
    int standaloneTrackControlWiringBuilds() {
        return standaloneTrackControlWiringBuilds;
    }

    /**
     * The wiring this view binds its strips through: the host's current
     * generation when injected, else a standalone one built lazily over this
     * view's project (and owned by it). Package-visible for tests.
     *
     * @return the wiring; never {@code null}
     */
    TrackControlWiring getTrackControlWiring() {
        TrackControlWiring hosted = hostedTrackControlWiring();
        if (hosted != null) {
            return hosted;
        }
        if (standaloneTrackControlWiring == null) {
            FxDispatcher dispatcher = fxDispatcher != null ? fxDispatcher : FxDispatcher.getDefault();
            if (dispatcher == null) {
                // Pure-unit context with no seam installed anywhere: own one.
                standaloneDispatcher = new FxDispatcher();
                dispatcher = standaloneDispatcher;
            }
            standaloneTrackControlWiring = TrackControlWiring.standalone(project, dispatcher, meterFeed);
            standaloneTrackControlWiringBuilds++;
        }
        return standaloneTrackControlWiring;
    }

    private TrackControlWiring hostedTrackControlWiring() {
        return trackControlWiringSupplier != null ? trackControlWiringSupplier.get() : null;
    }

    /**
     * Story 322 — brings the registry up to date with the project before the
     * strips are rebuilt: return-bus add/remove has no core signal, so
     * {@link #refresh()} is the one place it is guaranteed to be noticed. Only
     * an <em>existing</em> wiring is reconciled; a wiring built later derives
     * its VM set from the live project at construction.
     */
    private void reconcileTrackControlWiring() {
        TrackControlWiring wiring = hostedTrackControlWiring();
        if (wiring == null) {
            wiring = standaloneTrackControlWiring;
        }
        if (wiring != null) {
            wiring.registry().reconcile();
        }
    }

    private void disposeStandaloneTrackControlWiring() {
        if (standaloneTrackControlWiring != null) {
            standaloneTrackControlWiring.dispose();
            standaloneTrackControlWiring = null;
        }
        if (standaloneDispatcher != null) {
            standaloneDispatcher.dispose();
            standaloneDispatcher = null;
        }
    }

    /**
     * Registers the channel-link and undo-history listeners. Idempotent: the
     * constructor registers them once and the scene re-attach branch calls
     * this, so a view that is mounted, unmounted and mounted again ends with
     * exactly one registration of each.
     */
    private void acquireModelListeners() {
        if (modelListenersAttached) {
            return;
        }
        project.getChannelLinkManager().addListener(channelLinkListener);
        if (undoManager != null) {
            undoManager.addHistoryListener(undoHistoryListener);
        }
        modelListenersAttached = true;
    }

    /** The inverse of {@link #acquireModelListeners()}; run on scene detach. */
    private void releaseModelListeners() {
        if (!modelListenersAttached) {
            return;
        }
        project.getChannelLinkManager().removeListener(channelLinkListener);
        if (undoManager != null) {
            undoManager.removeHistoryListener(undoHistoryListener);
        }
        modelListenersAttached = false;
    }

    /**
     * Records a freshly built strip meter and, when a feed is already bound,
     * subscribes it immediately. Called from the strip builders so the
     * display, its tap point, and its subscription are created together.
     */
    private void registerStripMeter(LevelMeterDisplay display, MeterTapPoint point) {
        stripMeterPoints.put(display, point);
        // Not while detached: a refresh() driven from outside the Mixer (a track
        // created while another view is on screen) must not hand the app-scoped
        // feed live tokens on a view that has already left the scene graph and
        // may be replaced without ever coming back. The re-attach branch
        // subscribes everything from stripMeterPoints.
        if (!meterSceneDetached && meterFeedUsable()) {
            stripMeterBindings.add(bindMeter(display, point));
        }
    }

    /** Binds one display's subscription to its visibility. Only called with a usable feed. */
    private VisibleMeterBinding bindMeter(LevelMeterDisplay display, MeterTapPoint point) {
        return new VisibleMeterBinding(meterFeed, point, display,
                MeterSinks.levelMeterDisplay(display));
    }

    /**
     * A feed that has been disposed (primary stage hidden) rejects further
     * subscriptions, so a late rebuild must not try — the meters simply stay
     * at floor while the window tears down.
     */
    private boolean meterFeedUsable() {
        return meterFeed != null && !meterFeed.isDisposed();
    }

    /**
     * Disposes the track / return strip subscriptions (the master's survives a
     * refresh). {@link #stripMeterPoints} is deliberately <em>not</em> cleared:
     * a feed swap or a scene detach must keep knowing which meter shows which
     * tap point so the strips can be re-subscribed without being rebuilt. Only
     * {@link #refresh()}, which discards the strips themselves, clears it.
     */
    private void disposeStripMeterSubscriptions() {
        for (VisibleMeterBinding binding : stripMeterBindings) {
            binding.close();
        }
        stripMeterBindings.clear();
        // Story 322 — release the track strips' ChannelVM-owned subscriptions
        // too (the binder's and any re-attach rebind: unbindMeter drops every
        // surface binding of the VM — the mixer strip is the only surface that
        // binds a channel meter today). trackStripMeters is kept for the same
        // reason stripMeterPoints is: a re-attach re-subscribes without a
        // rebuild; only refresh() discards the strips and clears it.
        for (TrackStripMeter meter : trackStripMeters) {
            meter.channelVm().unbindMeter();
        }
    }

    /** Disposes every meter subscription this view owns, master included. */
    private void disposeAllMeterSubscriptions() {
        disposeStripMeterSubscriptions();
        if (masterMeterBinding != null) {
            masterMeterBinding.close();
            masterMeterBinding = null;
        }
    }

    /**
     * The return strip meters built by the last refresh (since story 322 the
     * track strips meter through their {@link ChannelVM} — see
     * {@link #getTrackStrips()}). Visible for testing.
     */
    List<LevelMeterDisplay> getStripMeterDisplays() {
        return List.copyOf(stripMeterPoints.keySet());
    }

    /** The return strip meter bindings, in strip order. Visible for testing. */
    List<VisibleMeterBinding> getStripMeterBindings() {
        return List.copyOf(stripMeterBindings);
    }

    /** The master strip's output meter. Visible for testing. */
    LevelMeterDisplay getMasterMeterDisplay() {
        return masterMeterDisplay;
    }

    /** The master strip's {@code MASTER_OUT} binding, or {@code null}. Visible for testing. */
    VisibleMeterBinding getMasterMeterBinding() {
        return masterMeterBinding;
    }

    /**
     * Configures the source of driver-reported input-channel metadata used
     * to populate the per-track input-routing dropdown — story 199. When
     * the supplier returns a non-empty list, the dropdown renders the
     * driver's display names (e.g. {@code "Mic/Line 1"},
     * {@code "S/PDIF L"}), shows a {@link ChannelKind} icon, auto-groups
     * consecutive {@code L}/{@code R} pairs into a single
     * {@code "<stem> (Stereo)"} entry, and greys out channels reported
     * inactive by the driver. The default supplier returns an empty list,
     * which preserves the legacy "Input N" / "Output N" dropdowns.
     *
     * @param supplier supplies the live input-channel metadata; must not be null
     */
    public void setInputChannelInfoSupplier(
            java.util.function.Supplier<List<AudioChannelInfo>> supplier) {
        this.inputChannelInfoSupplier = Objects.requireNonNull(
                supplier, "supplier must not be null");
    }

    /**
     * Output-side counterpart of {@link #setInputChannelInfoSupplier}. See
     * that method for the semantics.
     *
     * @param supplier supplies the live output-channel metadata; must not be null
     */
    public void setOutputChannelInfoSupplier(
            java.util.function.Supplier<List<AudioChannelInfo>> supplier) {
        this.outputChannelInfoSupplier = Objects.requireNonNull(
                supplier, "supplier must not be null");
    }

    /**
     * Story 215 — wires the notification manager used by
     * {@link #validateCueBusesAgainstDevice(NotificationManager)} when it
     * is called automatically during {@link #refresh()}.
     *
     * @param manager the notification manager; may be {@code null} to
     *                disable automatic cue-bus validation during refresh
     */
    public void setNotificationManager(NotificationManager manager) {
        this.notificationManager = manager;
    }

    /**
     * Rebuilds the channel strips from the current project tracks and return
     * buses.
     *
     * <p>Call this method after adding or removing tracks or return buses to
     * keep the mixer view synchronized with the project model.</p>
     */
    public void refresh() {
        if (disposed) return;
        // Story 322 — the VM registry must know every track / return bus /
        // the master BEFORE a strip binds through it.
        reconcileTrackControlWiring();
        // Drop any stale selections referencing tracks that no longer exist
        // so right-click "Create VCA from selection" can't pick up phantoms
        // after a track removal.
        Set<UUID> liveIds = new HashSet<>();
        for (Track t : project.getTracks()) {
            try {
                liveIds.add(UUID.fromString(t.getId()));
            } catch (IllegalArgumentException ignored) {
                // non-UUID id — skip
            }
        }
        selectedChannelIds.retainAll(liveIds);

        // Drop any stale solo-safe sync callbacks left over from the
        // previous build of strips so we don't poke discarded widgets.
        soloSafeSyncCallbacks.clear();
        // Dispose existing InsertEffectRack instances to prevent listener leaks
        for (InsertEffectRack rack : activeInsertRacks) {
            if (rack != masterInsertRack) rack.dispose();
        }
        activeInsertRacks.clear();
        if (masterInsertRack != null) activeInsertRacks.add(masterInsertRack);
        // Stop redraw timers on previously-constructed input-meter strips
        // so they don't keep firing (and holding references to discarded
        // JavaFX nodes) after a refresh.
        for (InputMeterStrip strip : activeInputMeterStrips) {
            strip.stop();
        }
        activeInputMeterStrips.clear();
        // Story 318 — the track / return strips (and their output meters) are
        // about to be discarded, so their tap-bus subscriptions go with them;
        // the builders below re-subscribe the freshly built meters. The master
        // strip is not rebuilt, so its MASTER_OUT subscription survives.
        disposeStripMeterSubscriptions();
        stripMeterPoints.clear();
        trackStripMeters.clear();

        // Story 322 — every strip control is a VM subscriber bound through
        // the wiring; the previous build's binders and listeners go with
        // their strips, and the send-row / channel lookups are rebuilt.
        disposeStripBindings();
        channelByChannelId.clear();
        sendRowsByChannelId.clear();
        TrackControlWiring wiring = getTrackControlWiring();

        channelStrips.getChildren().clear();
        // First pass: build each track's channel strip and remember its
        // backing channel id (when the track id is a UUID — non-UUID test
        // fixtures get a strip with no link affordance, matching the
        // existing VCA-wiring fallback).
        List<UUID> stripIdsInOrder = new ArrayList<>();
        for (Track track : project.getTracks()) {
            MixerChannel mixerChannel = project.getMixerChannelForTrack(track);
            if (mixerChannel != null) {
                channelStrips.getChildren().add(buildChannelStrip(track, mixerChannel, wiring));
                UUID id = parseChannelId(track);
                stripIdsInOrder.add(id);
                if (id != null) {
                    channelByChannelId.put(id, mixerChannel);
                }
            }
        }
        // Second pass: insert a small chain-glyph link toggle between every
        // adjacent pair of channel strips. The toggle pairs / unpairs the
        // two adjacent channels and (right-click) opens the link-detail
        // popover.
        installLinkToggles(stripIdsInOrder);

        returnBusStrips.getChildren().clear();
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            returnBusStrips.getChildren().add(buildReturnBusStrip(returnBus, wiring));
        }
        // Story 322 — the master strip is built once (constructor); re-bind
        // it against this generation's master ChannelVM.
        bindStandaloneStrip(project.getMixer().getMasterChannel(), masterStripControls, wiring);
        // "Add Return Bus" button at the end
        Button addReturnBusBtn = new Button("+");
        addReturnBusBtn.getStyleClass().add("track-arm-button");
        addReturnBusBtn.setTooltip(new Tooltip("Add Return Bus"));
        boolean atLimit = project.getMixer().getReturnBusCount() >= Mixer.MAX_RETURN_BUSES;
        addReturnBusBtn.setDisable(atLimit);
        if (atLimit) {
            addReturnBusBtn.setTooltip(new Tooltip(
                    "Maximum of " + Mixer.MAX_RETURN_BUSES + " return buses reached"));
        }
        addReturnBusBtn.setOnAction(_ -> {
            int busCount = project.getMixer().getReturnBusCount();
            String busName = "Return " + (busCount + 1);
            if (undoManager != null) {
                AddReturnBusAction action = new AddReturnBusAction(project.getMixer(), busName);
                undoManager.execute(action);
            } else {
                project.getMixer().addReturnBus(busName);
            }
            refresh();
        });
        returnBusStrips.getChildren().add(addReturnBusBtn);

        // ── Cue bus strips (Story 135) ──────────────────────────────────────
        // Render each registered CueBus as its own headphone-mix strip with
        // a label, hardware-output label, master fader, mute, "All Pre"
        // toggle, copy-main-mix helper, and remove button. A trailing "+"
        // button duplicates the Mixer-menu "New cue bus…" entry for quick
        // access.
        cueBusStrips.getChildren().clear();
        CueBusManager cueManager = project.getCueBusManager();
        // Story 215 — recompute the disabled-bus set before building
        // strips so buildCueBusStrip sees the current state.
        if (notificationManager != null) {
            validateCueBusesAgainstDevice(notificationManager);
        }
        // Prune cueBusPreMuteGain entries for buses that no longer exist
        // (e.g. removed between refreshes or after a project reload).
        Set<UUID> liveBusIds = cueManager.getCueBuses().stream()
                .map(CueBus::id).collect(Collectors.toSet());
        cueBusPreMuteGain.keySet().retainAll(liveBusIds);
        int cueIndex = 1;
        for (CueBus bus : cueManager.getCueBuses()) {
            cueBusStrips.getChildren().add(buildCueBusStrip(bus, cueIndex));
            cueIndex++;
        }
        Button addCueBusBtn = new Button("+");
        addCueBusBtn.getStyleClass().add("track-arm-button");
        addCueBusBtn.setTooltip(new Tooltip("New cue bus\u2026"));
        addCueBusBtn.setOnAction(_ -> promptCreateCueBus());
        cueBusStrips.getChildren().add(addCueBusBtn);

        // ── VCA strips (right of master, story 153) ──────────────────────
        vcaStrips.getChildren().clear();
        VcaGroupManager vcaManager = project.getVcaGroupManager();
        // A UUID→MixerChannel map over every live mixer channel and return
        // bus (a track channel's id is its track's UUID) so VCA strips can
        // resolve member channels — including members that have no track.
        Map<UUID, MixerChannel> channelMap = new java.util.HashMap<>();
        for (MixerChannel ch : project.getMixer().getChannels()) {
            channelMap.put(ch.getId(), ch);
        }
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            channelMap.put(returnBus.getId(), returnBus);
        }
        java.util.function.Function<UUID, MixerChannel> channelLookup = channelMap::get;
        // Story 322 — VCA mute / solo travel the one intent path: a member
        // with a track raises the track-targeted command (dual-write Track +
        // MixerChannel, so the arrangement lane follows), a track-less member
        // the channel-targeted one (§5.6 "VCA mute/solo").
        BiConsumer<MixerChannel, Boolean> vcaMuteIntent = (ch, muted) -> dispatchIntent(
                project.getTrackForChannel(ch)
                        .<TrackCommand>map(t -> new ToggleMuteCommand(t, muted))
                        .orElseGet(() -> new ToggleChannelMuteCommand(ch, muted)));
        BiConsumer<MixerChannel, Boolean> vcaSoloIntent = (ch, soloed) -> dispatchIntent(
                project.getTrackForChannel(ch)
                        .<TrackCommand>map(t -> new ToggleSoloCommand(t, soloed))
                        .orElseGet(() -> new ToggleChannelSoloCommand(ch, soloed)));
        for (VcaGroup vca : vcaManager.getVcaGroups()) {
            vcaStrips.getChildren().add(new VcaStrip(
                    vca, vcaManager, undoManager, channelLookup,
                    vcaMuteIntent, vcaSoloIntent, this::refresh));
        }
    }

    /**
     * Returns the container holding the VCA group strips. Visible for testing.
     */
    HBox getVcaStrips() {
        return vcaStrips;
    }

    /**
     * Returns the container holding the track channel strips (excluding master).
     * Visible for testing.
     */
    HBox getChannelStrips() {
        return channelStrips;
    }

    /**
     * Returns the container holding the return bus channel strips.
     * Visible for testing.
     */
    HBox getReturnBusStrips() {
        return returnBusStrips;
    }

    /**
     * Returns the master channel strip. Visible for testing.
     */
    VBox getMasterStrip() {
        return masterStrip;
    }

    /**
     * Builds one track's column: the host extras around a
     * {@link MixerChannelStrip} bound through the wiring (story 322 — the
     * story-271 skin swap). The column carries {@link #TRACK_STRIP_HOST_STYLE_CLASS},
     * not the legacy {@code .mixer-channel} alias: the strip paints its own
     * §5.4 surface and the column stays on the panel background.
     */
    private VBox buildChannelStrip(Track track, MixerChannel mixerChannel, TrackControlWiring wiring) {
        VBox strip = new VBox(4);
        strip.getStyleClass().add(TRACK_STRIP_HOST_STYLE_CLASS);
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);

        // The channel-id used by VCA membership and drag/drop is the track's
        // own UUID (its id is a UUID-formatted string per Track.java). Parse
        // once up front; if the id is not a UUID (forged test fixture) the
        // VCA wiring is skipped but the rest of the strip still renders.
        UUID parsedId = null;
        try {
            parsedId = UUID.fromString(track.getId());
        } catch (IllegalArgumentException ignored) {
            // skip VCA wiring for non-UUID ids
        }
        final UUID channelId = parsedId;

        if (channelId != null) {
            applyChannelStripSelectionStyle(strip, channelId);
        }

        // ── Member-VCA badge(s) at the top of the strip ───────────────────
        // Story 153: a small "VCA: <name> (<gain> dB)" label per group the
        // channel is currently a member of; story 322 adds the composite
        // readout from VcaGroupManager.effectiveGainDb (rebuilt on refresh).
        // Background uses the VCA's color so the user can see at a glance
        // which VCA(s) are riding this channel.
        VcaGroupManager vcaMgr = project.getVcaGroupManager();
        if (channelId != null) {
            List<VcaGroup> memberOf = vcaMgr.getGroupsForChannel(channelId);
            if (!memberOf.isEmpty()) {
                VBox badges = new VBox(1);
                badges.setAlignment(Pos.CENTER);
                for (VcaGroup g : memberOf) {
                    Label badge = new Label(vcaBadgeText(vcaMgr, g, channelId));
                    badge.getStyleClass().add("mixer-vca-badge");
                    badge.getStyleClass().add("mixer-channel-name");
                    String hex = g.color() != null ? g.color().getHexColor() : "#9c27b0";
                    badge.setStyle("-fx-background-color: " + hex + ";"
                            + " -fx-text-fill: #ffffff; -fx-padding: 1 4 1 4;"
                            + " -fx-font-size: 9px; -fx-background-radius: 3;");
                    badge.setMaxWidth(CHANNEL_WIDTH - 12);
                    badges.getChildren().add(badge);
                }
                strip.getChildren().add(badges);
            }
        }

        // ── Click selection (Ctrl/Shift toggles, plain click selects only) ─
        if (channelId != null) {
            strip.setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (e.isShiftDown() || e.isControlDown() || e.isMetaDown()) {
                    if (!selectedChannelIds.add(channelId)) {
                        selectedChannelIds.remove(channelId);
                    }
                } else {
                    selectedChannelIds.clear();
                    selectedChannelIds.add(channelId);
                }
                if (onChannelSelected != null) {
                    onChannelSelected.accept(project.getMixerChannelForTrack(track));
                }
                // Cheap restyle of every visible channel strip — no full refresh.
                for (Node n : channelStrips.getChildren()) {
                    if (n instanceof VBox box && box.getUserData() instanceof UUID id) {
                        applyChannelStripSelectionStyle(box, id);
                    }
                }
            });
            strip.setUserData(channelId);

            // ── Drag source: publishes the channel id so VCA strips can assign ─
            strip.setOnDragDetected(event -> {
                javafx.scene.input.Dragboard db =
                        strip.startDragAndDrop(javafx.scene.input.TransferMode.LINK);
                javafx.scene.input.ClipboardContent content =
                        new javafx.scene.input.ClipboardContent();
                content.put(VcaStrip.CHANNEL_ID_FORMAT, channelId.toString());
                content.putString(track.getName());
                db.setContent(content);
                event.consume();
            });

            // ── Right-click "Create VCA from selection" + assign submenu ───
            ContextMenu stripMenu = new ContextMenu();
            MenuItem createVcaItem = new MenuItem("Create VCA from selection");
            createVcaItem.setOnAction(_ -> createVcaFromSelection(channelId));
            stripMenu.getItems().add(createVcaItem);
            if (!vcaMgr.getVcaGroups().isEmpty()) {
                javafx.scene.control.Menu assignMenu =
                        new javafx.scene.control.Menu("Assign to VCA");
                for (VcaGroup g : vcaMgr.getVcaGroups()) {
                    boolean already = g.hasMember(channelId);
                    MenuItem item = new MenuItem((already ? "✓ " : "") + g.label());
                    item.setOnAction(_ -> {
                        AssignVcaMemberAction action = new AssignVcaMemberAction(
                                vcaMgr, g.id(), channelId, !already);
                        if (undoManager != null) {
                            undoManager.execute(action);
                        } else {
                            action.execute();
                        }
                        refresh();
                    });
                    assignMenu.getItems().add(item);
                }
                stripMenu.getItems().add(assignMenu);
            }
            // ── Story 100: channel-strip presets ────────────────────────────
            // "Save channel strip\u2026" captures the current insert chain +
            // sends + level/pan via TrackTemplateService.captureChannelStrip
            // and persists it as a ChannelStripPreset.  "Apply channel
            // strip\u2026" opens the preset browser and runs an
            // ApplyChannelStripPresetAction through the undo manager so the
            // change is reversible (the previous strip is restored on undo).
            if (trackTemplateController != null) {
                stripMenu.getItems().add(new SeparatorMenuItem());
                MenuItem saveStripItem = new MenuItem("Save channel strip\u2026");
                saveStripItem.setOnAction(_ -> trackTemplateController
                        .saveChannelStripAsPreset(mixerChannel));
                MenuItem applyStripItem = new MenuItem("Apply channel strip\u2026");
                applyStripItem.setOnAction(_ -> trackTemplateController
                        .applyChannelStripPreset(mixerChannel));
                stripMenu.getItems().addAll(saveStripItem, applyStripItem);
            }
            // ── Story 035: Freeze / Unfreeze on the mixer strip menu ────────
            if (trackFreezeController != null) {
                stripMenu.getItems().add(new SeparatorMenuItem());
                MenuItem freezeStrip = new MenuItem("\u2744 Freeze track");
                freezeStrip.setOnAction(_ -> trackFreezeController.freezeTrack(track));
                MenuItem unfreezeStrip = new MenuItem("\u2744 Unfreeze track");
                unfreezeStrip.setOnAction(_ -> trackFreezeController.unfreezeTrack(track));
                stripMenu.getItems().addAll(freezeStrip, unfreezeStrip);
                stripMenu.setOnShowing(_ -> {
                    boolean isFrozen = track.isFrozen();
                    freezeStrip.setDisable(isFrozen);
                    unfreezeStrip.setDisable(!isFrozen);
                });
            }
            // Story 129 (UI) — "CPU Budget…" entry opens a small
            // dialog to set the per-track maxFractionOfBlock and the
            // DegradationPolicy. Hidden when no handler is wired.
            if (onConfigureCpuBudget != null) {
                stripMenu.getItems().add(new SeparatorMenuItem());
                MenuItem cpuBudgetItem = new MenuItem("CPU Budget\u2026");
                cpuBudgetItem.setOnAction(_ -> onConfigureCpuBudget.accept(mixerChannel));
                stripMenu.getItems().add(cpuBudgetItem);
            }
            strip.setOnContextMenuRequested(e ->
                    stripMenu.show(strip, e.getScreenX(), e.getScreenY()));
        }

        // The channel name is rendered by the MixerChannelStrip itself (at
        // its foot, UI Design Book §5.4) and follows TrackVM.name through the
        // binder — no host name label since story 322. The status badges
        // that used to sit under that label now lead the column.

        // ── ❄ Snowflake "frozen" status indicator (Story 035) ──────────────
        // A small snowflake badge is mounted at the top of the column on
        // every frozen track, but only when the freeze controller is
        // wired. When null, the badge is suppressed so the API contract of
        // setTrackFreezeController is honoured.
        Label freezeBadge = null;
        if (track.isFrozen() && trackFreezeController != null) {
            freezeBadge = new Label("\u2744");
            freezeBadge.setStyle("-fx-text-fill: #5fa8ff; -fx-font-size: 12px;"
                    + " -fx-padding: 0 2 0 2;");
            String tip = trackFreezeController.tooltipFor(track);
            if (tip != null && !tip.isEmpty()) {
                Tooltip.install(freezeBadge, new Tooltip(tip));
            }
        }

        // ── L / R badge for a member of a stereo pair (Story 159) ─────────
        // The chain-link manager owns the (left, right) ordering — render
        // a single-letter badge under the strip name so the engineer can
        // see at a glance which strip is the source / mirrored side.
        Label lrBadge = null;
        if (channelId != null) {
            ChannelLink existingLink = project.getChannelLinkManager().getLink(channelId);
            if (existingLink != null) {
                boolean isLeft = existingLink.leftChannelId().equals(channelId);
                lrBadge = new Label(isLeft ? "L" : "R");
                lrBadge.getStyleClass().add("mixer-channel-link-badge");
                lrBadge.setStyle("-fx-background-color: #00bcd4;"
                        + " -fx-text-fill: #0d0d0d;"
                        + " -fx-font-weight: bold;"
                        + " -fx-padding: 0 4 0 4;"
                        + " -fx-font-size: 9px;"
                        + " -fx-background-radius: 3;");
            }
        }

        // ── Routing selectors — built before the strip so its I/O captions
        // can mirror the current selection (UI Design Book §5.4 "I In / O Out").
        ComboBox<IoOption> inputRoutingCombo = buildInputRoutingSelector(track);
        ComboBox<IoOption> outputRoutingCombo = buildOutputRoutingSelector(mixerChannel);

        // ── The story-271 MixerChannelStrip (story 322: a pure skin swap) ──
        // Name, insert list, pan, dB fader with its integrated meter and the
        // M/S/R toggles are all VM subscriptions bound in bindTrackStrip
        // (TrackControlBinder.bindStrip); the strip writes no model itself
        // (Audio Engine Wiring Design Book §2.10). Its meter is the
        // channel's post-fader CHANNEL_POST tap, subscribed by the ChannelVM
        // with the strip as the visibility-owning surface — no
        // LevelMeterDisplay is registered for a track strip any more. The
        // send list stays EMPTY: the skin's send fader has no write-back, so
        // a fed row would be a dead control; the per-return send rows below
        // are the one send surface.
        MixerChannelStrip channelStrip = new MixerChannelStrip();
        channelStrip.setChannelId(channelId);
        channelStrip.setChannelName(track.getName());
        channelStrip.setChannelType(stripTypeFor(track.getType()));
        mirrorIoCaptions(channelStrip, inputRoutingCombo, outputRoutingCombo);
        // Solo safe (solo-in-place defeat): the :solo-safe ring on the strip
        // and the right-click "Solo safe" menu on its S toggle, both a
        // function of the model — never of a click.
        applySoloSafeRing(channelStrip, mixerChannel);
        installSoloSafeContextMenu(channelStrip, mixerChannel);

        // ── Input meter (armed tracks only) beside the strip ───────────
        // Story 137: when a track is armed, show a dedicated input-signal
        // meter column ahead of the strip (whose integrated meter is the
        // post-fader output) with a latching clip LED. Clicking the clip
        // LED resets that track; Alt+click resets all.
        HBox stripRow = new HBox(2);
        stripRow.setAlignment(Pos.TOP_CENTER);
        if (track.isArmed() && inputLevelMonitorRegistry != null) {
            InputLevelMonitor monitor = inputLevelMonitorRegistry.getOrCreate(track);
            InputMeterStrip inputStrip = new InputMeterStrip(monitor, inputLevelMonitorRegistry);
            inputStrip.setPrefWidth(INPUT_METER_WIDTH);
            inputStrip.setMinWidth(INPUT_METER_WIDTH);
            inputStrip.setMaxWidth(INPUT_METER_WIDTH);
            inputStrip.setPrefHeight(METER_HEIGHT);
            inputStrip.setMinHeight(METER_HEIGHT);
            activeInputMeterStrips.add(inputStrip);
            stripRow.getChildren().add(inputStrip);
        }
        stripRow.getChildren().add(channelStrip);

        // 3D Panner button — story 322: visible (and laid out) only while the
        // channel's insert chain holds a spatial node (§5.6 "3D panner
        // button"); bound to ChannelVM.spatialNodePresent in bindTrackStrip.
        Button pannerBtn = new Button("3D");
        pannerBtn.getStyleClass().add("track-arm-button");
        pannerBtn.setTooltip(new Tooltip("Open 3D Spatial Panner"));
        pannerBtn.setGraphic(IconNode.of(DawIcon.SURROUND, CONTROL_ICON_SIZE));
        pannerBtn.setOnAction(_ -> {
            SpatialPannerController controller = new SpatialPannerController(
                    SpatialPannerController.createDefaultPanner(SpeakerLayout.LAYOUT_7_1_4),
                    track.getName());
            controller.openWindow();
        });

        TrackStripHandles handles = new TrackStripHandles(channelStrip, pannerBtn);
        strip.getProperties().put(TRACK_STRIP_KEY, handles);
        bindTrackStrip(track, mixerChannel, channelId, handles, wiring);

        // Send controls — one slider per return bus with active-routing indicator
        VBox sendBox = new VBox(2);
        for (MixerChannel returnBus : project.getMixer().getReturnBuses()) {
            Circle sendIndicator = new Circle(4);
            Send existingSend = mixerChannel.getSendForTarget(returnBus);
            double initialLevel = existingSend != null ? existingSend.getLevel() : 0.0;
            sendIndicator.setFill(initialLevel > 0.0 ? Color.web("#00e676") : Color.web("#555555"));

            Label sendLabel = new Label("→ " + returnBus.getName());
            sendLabel.getStyleClass().add("mixer-channel-name");
            sendLabel.setMaxWidth(CHANNEL_WIDTH - 12);
            sendLabel.setGraphic(sendIndicator);

            Slider sendSlider = new Slider(0.0, 1.0, initialLevel);
            sendSlider.setPrefWidth(SEND_SLIDER_WIDTH);
            sendSlider.getStyleClass().add("mixer-fader");
            sendSlider.setTooltip(new Tooltip("Send to " + returnBus.getName()));

            // Compact tap-point cycler ("I" pre-inserts / "F" pre-fader /
            // "P" post-fader) — a single letter the user can click to cycle
            // through the three tap positions. Tooltip explains all three
            // states. The button reflects the current tap of the send (or
            // the default POST_FADER when no send exists yet).
            Button tapButton = new Button();
            tapButton.getStyleClass().add("mixer-send-tap");
            tapButton.setStyle("-fx-padding: 0 4 0 4; -fx-font-size: 10px;");
            tapButton.setTooltip(new Tooltip(
                    "Send tap point — click to cycle:\n"
                            + "  I = pre-Inserts (before any insert effect)\n"
                            + "  F = pre-Fader (after inserts, before fader)\n"
                            + "  P = Post-fader (default)"));

            MixerChannel targetBus = returnBus;
            // Capture the send state before a drag starts so that undo restores
            // to the pre-drag state (the slider listener modifies the model
            // live) — the source's and, when the pair is linked with "Link
            // Sends", the partner's: the per-tick mirror rewrites the
            // partner's send too, so its undo record needs the same
            // pre-gesture baseline (story 322 fix round 1, S6). The partner
            // is resolved at press time so release restores exactly the send
            // the mirror moved.
            SendState[] dragStart = {SendState.of(existingSend)};
            MixerChannel[] partnerAtDragStart = {null};
            SendState[] partnerDragStart = {SendState.ABSENT};

            sendSlider.setOnMousePressed(_ -> {
                dragStart[0] = SendState.of(mixerChannel.getSendForTarget(targetBus));
                MixerChannel partner = linkedSendPartner(channelId).orElse(null);
                partnerAtDragStart[0] = partner;
                partnerDragStart[0] = partner == null
                        ? SendState.ABSENT : SendState.of(partner.getSendForTarget(targetBus));
            });

            sendSlider.valueProperty().addListener((_, _, newVal) -> {
                double value = newVal.doubleValue();
                sendIndicator.setFill(value > 0.0 ? Color.web("#00e676") : Color.web("#555555"));
                Send send = mixerChannel.getSendForTarget(targetBus);
                if (send != null) {
                    if (send.getLevel() == value) {
                        return; // echo of a model-driven reflection (link mirror), not a gesture
                    }
                    send.setLevel(value);
                } else if (value > 0.0) {
                    send = new Send(targetBus, value, SendTap.POST_FADER);
                    mixerChannel.addSend(send);
                } else {
                    return;
                }
                // Story 322 — "Link Sends": the stereo partner follows the model write.
                mirrorSendToPartner(channelId, targetBus, value, send.getTap());
            });

            // Commit ONE undoable action when the user finishes dragging the
            // slider. The pre-drag model state is restored first so that
            // execute() captures the correct previousLevel / hadSendBefore
            // for undo, then the final value is re-applied — for the source
            // and, when the pair is linked with "Link Sends", for the partner
            // the per-tick mirror moved. A linked drag is a single compound
            // entry, so Ctrl+Z restores both sends and redo re-applies both
            // (§5.6 "Stereo link"); the rows re-seed from the model on the
            // history event (reseedSendRows). A release whose live send
            // state — the source's and the captured partner's — still equals
            // the pre-drag capture (a click without a move, a drag back to
            // its start) returns before any restore, step or entry (fix
            // round 2): the tick listener creates nothing for such a
            // gesture, and SetSendRoutingAction would otherwise create a 0.0
            // send on both members of a linked pair — a model write no
            // gesture asked for, which a SEND_LEVEL lane would then drive
            // (§5.6: nothing dead, nothing silent). SendState is a record,
            // so the compare is structural (existence, level, tap); the
            // level is the double the tick wrote, so equality is exact.
            sendSlider.setOnMouseReleased(_ -> {
                if (undoManager == null) {
                    return;
                }
                Send send = mixerChannel.getSendForTarget(targetBus);
                MixerChannel partner = partnerAtDragStart[0];
                boolean sourceMoved = !SendState.of(send).equals(dragStart[0]);
                boolean partnerMoved = partner != null
                        && !SendState.of(partner.getSendForTarget(targetBus)).equals(partnerDragStart[0]);
                if (!sourceMoved && !partnerMoved) {
                    return;
                }
                double finalValue = sendSlider.getValue();
                SendMode mode = send != null ? send.getMode() : SendMode.POST_FADER;
                SendTap tap = send != null ? send.getTap() : SendTap.POST_FADER;

                dragStart[0].restore(mixerChannel, targetBus);
                List<UndoableAction> steps = new ArrayList<>();
                steps.add(new SetSendRoutingAction(mixerChannel, targetBus, finalValue, mode));
                if (partner != null) {
                    partnerDragStart[0].restore(partner, targetBus);
                    steps.addAll(partnerSendSteps(partner, targetBus, finalValue, mode, tap));
                }
                undoManager.execute(asOneAction("Set Send Routing", steps));
            });

            // Right-click context menu and tap-cycler button to choose the
            // send tap point. Both delegate to SetSendTapAction so changes
            // are undoable and consistent with the rest of the mixer.
            Runnable refreshTapButton = () -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                SendTap currentTap = s != null ? s.getTap() : SendTap.POST_FADER;
                tapButton.setText(switch (currentTap) {
                    case PRE_INSERTS -> "I";
                    case PRE_FADER   -> "F";
                    case POST_FADER  -> "P";
                });
            };
            refreshTapButton.run();

            Consumer<SendTap> applyTap = newTap -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                if (s == null) {
                    return; // nothing to update until the send exists
                }
                // Story 322 — "Link Sends": the tap point mirrors to the
                // partner too. With an undo manager the pair's edit is ONE
                // compound entry (fix round 1, S6) whose partner steps do
                // exactly what the live mirror does; without one the live
                // mirror runs as before.
                MixerChannel partner = linkedSendPartner(channelId).orElse(null);
                if (undoManager != null) {
                    List<UndoableAction> steps = new ArrayList<>();
                    steps.add(new SetSendTapAction(mixerChannel, targetBus, newTap));
                    if (partner != null) {
                        steps.addAll(partnerSendSteps(partner, targetBus, s.getLevel(), s.getMode(), newTap));
                    }
                    undoManager.execute(asOneAction("Set Send Tap", steps));
                } else {
                    s.setTap(newTap);
                    mirrorSendToPartner(channelId, targetBus, s.getLevel(), s.getTap());
                }
                refreshTapButton.run();
                if (partner != null) {
                    reflectSendRow(partner, targetBus);
                }
            };

            tapButton.setOnAction(_ -> {
                Send s = mixerChannel.getSendForTarget(targetBus);
                if (s == null) {
                    // No send exists yet: don't auto-create one (that path is
                    // not undoable) — the user must raise the slider first to
                    // create a send, then cycle the tap point.
                    return;
                }
                SendTap next = switch (s.getTap()) {
                    case POST_FADER  -> SendTap.PRE_FADER;
                    case PRE_FADER   -> SendTap.PRE_INSERTS;
                    case PRE_INSERTS -> SendTap.POST_FADER;
                };
                applyTap.accept(next);
            });

            ContextMenu sendMenu = new ContextMenu();
            MenuItem preInsertsItem = new MenuItem("Pre-Inserts");
            preInsertsItem.setOnAction(_ -> applyTap.accept(SendTap.PRE_INSERTS));
            MenuItem preFaderItem = new MenuItem("Pre-Fader");
            preFaderItem.setOnAction(_ -> applyTap.accept(SendTap.PRE_FADER));
            MenuItem postFaderItem = new MenuItem("Post-Fader");
            postFaderItem.setOnAction(_ -> applyTap.accept(SendTap.POST_FADER));
            sendMenu.getItems().addAll(preInsertsItem, preFaderItem, postFaderItem);
            sendLabel.setContextMenu(sendMenu);

            HBox sliderRow = new HBox(2, sendSlider, tapButton);
            sliderRow.setAlignment(Pos.CENTER_LEFT);
            sendBox.getChildren().addAll(sendLabel, sliderRow);
            if (channelId != null) {
                sendRowsByChannelId
                        .computeIfAbsent(channelId, _ -> new LinkedHashMap<>())
                        .put(targetBus, new SendRow(sendSlider, tapButton, refreshTapButton));
            }
        }

        // ── Cue sends (Story 135) ──────────────────────────────────────────
        // Per-channel cue sends — one row per registered CueBus. Default to
        // PRE_FADER so a performer's monitor mix doesn't flinch when the
        // engineer pulls down the control-room fader. The section is
        // collapsible (suppressed entirely) when no cue buses exist.
        UUID trackUuid = parseChannelId(track);
        CueBusManager cueMgr = project.getCueBusManager();
        if (trackUuid != null && !cueMgr.getCueBuses().isEmpty()) {
            VBox cueSendsBox = new VBox(2);
            Label cueHeader = new Label("CUE");
            cueHeader.getStyleClass().add("mixer-channel-name");
            cueHeader.setStyle("-fx-text-fill: #ffd54f; -fx-font-weight: bold;"
                    + " -fx-font-size: 9px;");
            cueSendsBox.getChildren().add(cueHeader);
            int idx = 1;
            for (CueBus cueBus : cueMgr.getCueBuses()) {
                cueSendsBox.getChildren().add(
                        buildCueSendRow(trackUuid, cueBus, idx));
                idx++;
            }
            sendBox.getChildren().add(cueSendsBox);
        }

        // Story 322 — the legacy "SEND" slider (a dead sink into the removed
        // MixerChannel.sendLevel) is gone; the per-return send rows above
        // are the one send model.

        // Track type icon
        Node typeIcon = trackTypeIcon(track.getType());

        // Insert effects rack — still the editing surface (story 320); the
        // strip's insert rows above are the §5.4 indicator list whose click
        // selects the insert in the Inspector.
        int channels = project.getFormat().channels();
        double sr = project.getFormat().sampleRate();
        int bs = project.getFormat().bufferSize();
        InsertEffectRack insertRack = new InsertEffectRack(mixerChannel, channels, sr, bs, undoManager, fxDispatcher);
        insertRack.setPluginRegistry(pluginRegistry);
        insertRack.setMixer(project.getMixer());
        insertRack.setOnOpenEditor(onOpenInsertEditor);
        insertRack.setMeterFeed(meterFeed);
        insertRack.setDragVisualAdvisor(dragVisualAdvisor);
        activeInsertRacks.add(insertRack);

        // Per-channel latency label for plugin delay compensation (PDC)
        Label latencyLabel = new Label();
        latencyLabel.getStyleClass().add("mixer-channel-name");
        // Story 266 / §3.2 — tabular figures for the "%.1f ms" readout via
        // family-only .numeric-mono; the 9 px channel-strip size set inline
        // below would fight .numeric-caption's 11 px (inline wins on size
        // but the size mismatch is misleading).
        latencyLabel.getStyleClass().add("numeric-mono");
        latencyLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        latencyLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
        updateLatencyLabel(latencyLabel, mixerChannel, sr);

        // Update the latency label whenever inserts are added/removed/reordered/bypassed
        insertRack.setOnSlotsChanged(() -> updateLatencyLabel(latencyLabel, mixerChannel, sr));

        // Story 129 (UI) — "⚠" CPU-degraded badge. Rendered at the top of
        // the column (after the L/R + freeze badges) when the per-track CPU
        // budget enforcer reports the channel as degraded. Cleared on the
        // next refresh once the binding sees a TrackRestored event.
        Label degradedBadge = null;
        if (degradedTrackPredicate.test(track.getId())) {
            degradedBadge = new Label("\u26A0");
            degradedBadge.getStyleClass().add("mixer-channel-degraded-badge");
            degradedBadge.setStyle("-fx-text-fill: #ff9100; -fx-font-size: 12px;"
                    + " -fx-padding: 0 2 0 2;");
            Tooltip.install(degradedBadge,
                    new Tooltip("Track exceeded CPU budget; degradation policy active"));
        }

        // Column order: the status badges (L/R, frozen, degraded) lead - they
        // used to sit under the host name label, which the strip now renders
        // itself at its foot - then the type icon, routing selectors, rack +
        // latency, the strip row (input meter beside the strip while armed),
        // the gated 3D button and the send rows. VCA badges were added first.
        if (lrBadge != null) {
            strip.getChildren().add(lrBadge);
        }
        if (freezeBadge != null) {
            strip.getChildren().add(freezeBadge);
        }
        if (degradedBadge != null) {
            strip.getChildren().add(degradedBadge);
        }
        strip.getChildren().addAll(
                typeIcon,
                inputRoutingCombo, outputRoutingCombo,
                insertRack, latencyLabel,
                stripRow, pannerBtn,
                sendBox);

        return strip;
    }

    /**
     * The {@link MixerChannelStrip.ChannelType} a track's strip renders as.
     * {@code TrackType.MASTER} maps to {@code BUS}, not {@code MASTER}: the
     * strip type {@code MASTER} removes the M/S/R row (UI Design Book §5.4 —
     * that is the master <em>bus</em> strip, which this view builds
     * separately), whereas a {@code MASTER} <em>track</em> (session-interchange
     * import) is an ordinary track with a mixer channel that must stay mutable
     * and soloable. Bus-like track types hide the input caption; the
     * audio-bearing ones keep the full I/O + M/S/R surface.
     */
    private static MixerChannelStrip.ChannelType stripTypeFor(TrackType type) {
        return switch (type) {
            case AUDIO, BED_CHANNEL, AUDIO_OBJECT, REFERENCE -> MixerChannelStrip.ChannelType.AUDIO;
            case MIDI -> MixerChannelStrip.ChannelType.MIDI;
            case AUX, FOLDER, MASTER -> MixerChannelStrip.ChannelType.BUS;
        };
    }

    /**
     * Seeds the strip's I/O captions from the routing selectors' current
     * selection and keeps them following it. The listeners reference only the
     * strip and the combos, which live and die with the column, so no disposer
     * is needed.
     */
    private static void mirrorIoCaptions(MixerChannelStrip strip,
                                         ComboBox<IoOption> inputRoutingCombo,
                                         ComboBox<IoOption> outputRoutingCombo) {
        strip.setInputLabel(captionOf(inputRoutingCombo.getSelectionModel().getSelectedItem()));
        inputRoutingCombo.getSelectionModel().selectedItemProperty().addListener(
                (_, _, now) -> strip.setInputLabel(captionOf(now)));
        strip.setOutputLabel(captionOf(outputRoutingCombo.getSelectionModel().getSelectedItem()));
        outputRoutingCombo.getSelectionModel().selectedItemProperty().addListener(
                (_, _, now) -> strip.setOutputLabel(captionOf(now)));
    }

    private static String captionOf(IoOption option) {
        return option == null ? "" : option.displayName();
    }

    /**
     * Story 135 — builds a single cue-send row (gain slider + pan slider +
     * pre/post tap toggle) for a track contributing to a {@link CueBus}. The
     * tap toggle cycles between {@code PRE_FADER} (the cue-send default — a
     * performer's mix is stable while the engineer chases the control-room
     * mix) and {@code POST_FADER}. The row reads from
     * {@link CueBus#findSend(UUID)} on every render so it always reflects the
     * current snapshot in {@link CueBusManager}.
     */
    private HBox buildCueSendRow(UUID trackId, CueBus cueBus, int displayIndex) {
        UUID busId = cueBus.id();
        CueSend existing = cueBus.findSend(trackId);
        double initialGain = existing != null ? existing.gain() : 0.0;
        double initialPan = existing != null ? existing.pan() : 0.0;
        boolean initialPre = existing == null || existing.preFader();

        String shortLabel = "C" + displayIndex;
        Label rowLabel = new Label(shortLabel);
        rowLabel.getStyleClass().add("mixer-channel-name");
        rowLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #ffd54f;");
        Tooltip.install(rowLabel, new Tooltip(
                "Cue send to "
                        + (cueBus.label() == null || cueBus.label().isBlank()
                                ? "Cue " + displayIndex : cueBus.label())));

        Slider gainSlider = new Slider(0.0, 1.0, initialGain);
        gainSlider.setPrefWidth(SEND_SLIDER_WIDTH);
        gainSlider.getStyleClass().add("mixer-fader");
        gainSlider.setTooltip(new Tooltip("Cue send gain"));

        Slider panSlider = new Slider(-1.0, 1.0, initialPan);
        panSlider.setPrefWidth(SEND_SLIDER_WIDTH / 2.0);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Cue send pan"));

        Button tapBtn = new Button(initialPre ? "F" : "P");
        tapBtn.setStyle("-fx-padding: 0 4 0 4; -fx-font-size: 10px;");
        tapBtn.setTooltip(new Tooltip(
                "Cue send tap point — click to cycle:\n"
                        + "  F = pre-Fader (default for cue sends)\n"
                        + "  P = Post-fader"));

        Runnable applyToManager = () -> {
            CueBus current = project.getCueBusManager().getById(busId);
            if (current == null) return;
            double g = gainSlider.getValue();
            double p = panSlider.getValue();
            CueBus updated;
            if (g <= 0.0 && current.findSend(trackId) == null) {
                // No existing send and the user hasn't moved the gain slider
                // — skip creating a new zero-gain entry. Note: if a send
                // already exists and the user drags gain to 0.0 it will
                // remain in the model (allowing the user to adjust pan/tap
                // without losing the entry).
                return;
            }
            updated = current.withSend(new CueSend(trackId, g, p, "F".equals(tapBtn.getText())));
            project.getCueBusManager().replace(updated);
        };

        gainSlider.valueProperty().addListener((_, _, _) -> applyToManager.run());
        panSlider.valueProperty().addListener((_, _, _) -> applyToManager.run());
        tapBtn.setOnAction(_ -> {
            tapBtn.setText("F".equals(tapBtn.getText()) ? "P" : "F");
            applyToManager.run();
        });

        HBox row = new HBox(2, rowLabel, gainSlider, panSlider, tapBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildReturnBusStrip(MixerChannel returnBus, TrackControlWiring wiring) {
        VBox strip = new VBox(4);
        strip.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && onChannelSelected != null) {
                onChannelSelected.accept(returnBus);
            }
        });
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);
        strip.setStyle("-fx-border-color: #00bcd4;");

        Label nameLabel = new Label(returnBus.getName());
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        nameLabel.setStyle("-fx-text-fill: #00e5ff; -fx-font-weight: bold;");

        // Story 318 — the post-fader RETURN_POST tap of this bus.
        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);
        registerStripMeter(levelMeter, new MeterTapPoint.ReturnPost(returnBus.getId()));

        // Story 322 — fader / pan / mute / solo are VM subscribers bound
        // through a ChannelControlBinder (bindStandaloneStrip); the return
        // pan is live in the engine since the bus pan law landed.
        Slider volumeFader = new Slider(0.0, 1.0, returnBus.getVolume());
        volumeFader.setOrientation(Orientation.VERTICAL);
        volumeFader.setPrefHeight(FADER_HEIGHT);
        volumeFader.getStyleClass().add("mixer-fader");
        volumeFader.setTooltip(new Tooltip("Return Bus Volume"));

        Slider panSlider = new Slider(-1.0, 1.0, returnBus.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Return Bus Pan"));
        Label panLabel = new Label("PAN");
        panLabel.getStyleClass().add("mixer-channel-name");

        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute Return Bus"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));

        // Remove return bus button
        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().add("track-arm-button");
        removeBtn.setTooltip(new Tooltip("Remove Return Bus"));
        removeBtn.setOnAction(_ -> {
            // Check if any channels have active sends targeting this bus
            boolean hasActiveSends = project.getMixer().getChannels().stream()
                    .map(ch -> ch.getSendForTarget(returnBus))
                    .anyMatch(send -> send != null && send.getLevel() > 0.0);

            if (hasActiveSends) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Remove Return Bus");
                confirm.setHeaderText("Active sends exist");
                confirm.setContentText(
                        "One or more channels have active sends targeting \""
                                + returnBus.getName()
                                + "\". Removing it will also remove those sends. Continue?");
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) {
                    return;
                }
            }

            if (undoManager != null) {
                RemoveReturnBusAction action = new RemoveReturnBusAction(
                        project.getMixer(), returnBus);
                undoManager.execute(action);
            } else {
                project.getMixer().removeReturnBus(returnBus);
            }
            refresh();
        });

        // Solo button — return buses don't usually solo, but they expose the
        // same right-click "Solo Safe" toggle as track strips so users can
        // turn off solo-safe on a return bus that they want to silence under
        // solo (e.g. a parallel-compression bus they want to A/B).
        Button soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, CONTROL_ICON_SIZE));
        applySoloButtonStyle(soloBtn, returnBus);
        installSoloSafeContextMenu(soloBtn, returnBus);

        HBox buttonRow = new HBox(2, muteBtn, soloBtn, removeBtn);
        buttonRow.setAlignment(Pos.CENTER);

        MixerStripControls controls = new MixerStripControls(
                volumeFader, panSlider, muteBtn, soloBtn, null, null);
        strip.getProperties().put(STRIP_CONTROLS_KEY, controls);
        bindStandaloneStrip(returnBus, controls, wiring);
        int channels = project.getFormat().channels();
        double sr = project.getFormat().sampleRate();
        int bs = project.getFormat().bufferSize();
        InsertEffectRack insertRack = new InsertEffectRack(returnBus, channels, sr, bs, undoManager, fxDispatcher);
        insertRack.setPluginRegistry(pluginRegistry);
        insertRack.setMixer(project.getMixer());
        insertRack.setOnOpenEditor(onOpenInsertEditor);
        insertRack.setMeterFeed(meterFeed);
        insertRack.setDragVisualAdvisor(dragVisualAdvisor);
        activeInsertRacks.add(insertRack);

        // Per-bus latency label for plugin delay compensation (PDC)
        Label latencyLabel = new Label();
        latencyLabel.getStyleClass().add("mixer-channel-name");
        // Story 266 / §3.2 — tabular figures via family-only .numeric-mono;
        // the 9 px inline size below would fight .numeric-caption's 11 px.
        latencyLabel.getStyleClass().add("numeric-mono");
        latencyLabel.setMaxWidth(CHANNEL_WIDTH - 12);
        latencyLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");
        updateLatencyLabel(latencyLabel, returnBus, sr);

        // Update the latency label whenever inserts are added/removed/reordered/bypassed
        insertRack.setOnSlotsChanged(() -> updateLatencyLabel(latencyLabel, returnBus, sr));

        Node busIcon = IconNode.of(DawIcon.MIXER, CONTROL_ICON_SIZE);

        strip.getChildren().addAll(
                nameLabel, busIcon, insertRack, latencyLabel, levelMeter, volumeFader,
                panLabel, panSlider, buttonRow);

        return strip;
    }

    /**
     * Story 135 — builds a "CUE N" master strip for a {@link CueBus}.
     *
     * <p>The strip carries the cue label, a hardware-output label
     * ("Out 3/4"), a master fader, mute, PFL (pre-fader listen) toggle,
     * a "Copy main mix" helper that snapshots the current track faders
     * into the cue's sends, and a remove button. Distinct visual treatment
     * (yellow accent) separates cue strips from return-bus / master / VCA
     * strips so engineers don't confuse them during a tracking session.
     */
    private VBox buildCueBusStrip(CueBus bus, int displayIndex) {
        VBox strip = new VBox(4);
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);
        strip.setStyle("-fx-border-color: #ffd54f;");

        // Story 215 — strips for buses whose hardware output pair is no
        // longer present in the live driver are visually flagged so the
        // user knows the bus is silently inactive. Only the fader is
        // disabled — the remove and copy buttons stay interactive so
        // the user can still delete or reconfigure the bus.
        boolean unrouted = disabledCueBusIds.contains(bus.id());
        if (unrouted) {
            strip.setOpacity(0.55);
            strip.setStyle("-fx-border-color: #ff5252;");
            Tooltip.install(strip, new Tooltip(
                    "Cue bus output pair is not present on the current "
                            + "audio device — remove or re-assign to fix."));
        }

        Label kindLabel = new Label("CUE " + displayIndex);
        kindLabel.getStyleClass().add("mixer-channel-name");
        kindLabel.setStyle("-fx-text-fill: #ffd54f; -fx-font-weight: bold;");

        String labelText = bus.label() == null || bus.label().isBlank()
                ? "Cue " + displayIndex : bus.label();
        Label nameLabel = new Label(labelText);
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(CHANNEL_WIDTH - 12);

        // Hardware-output label — story 135 routes a stereo pair to physical
        // outputs (2N / 2N+1). Display 1-based numbers because that is what
        // engineers see on the back of the audio interface.
        int outA = bus.hardwareOutputIndex() * 2 + 1;
        int outB = outA + 1;
        Label outLabel = new Label("Out " + outA + "/" + outB);
        outLabel.getStyleClass().add("mixer-channel-name");
        outLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #aaaaaa;");

        Slider masterFader = new Slider(0.0, 1.0, bus.masterGain());
        masterFader.setOrientation(Orientation.VERTICAL);
        masterFader.setPrefHeight(FADER_HEIGHT);
        masterFader.getStyleClass().add("mixer-fader");
        masterFader.setTooltip(new Tooltip("Cue Bus Master Gain"));
        if (unrouted) {
            masterFader.setDisable(true);
        }
        masterFader.valueProperty().addListener((_, _, v) -> {
            CueBus current = project.getCueBusManager().getById(bus.id());
            if (current != null) {
                project.getCueBusManager().replace(current.withMasterGain(v.doubleValue()));
            }
        });

        // Mute is implemented via masterGain=0; the pre-mute gain is persisted
        // in cueBusPreMuteGain so it survives refresh() rebuilds.
        boolean isMuted = bus.masterGain() <= 0.0;
        if (!isMuted && bus.masterGain() > 0.0) {
            cueBusPreMuteGain.put(bus.id(), bus.masterGain());
        }
        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute Cue Bus"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));
        muteBtn.setOnAction(_ -> {
            CueBus current = project.getCueBusManager().getById(bus.id());
            if (current == null) return;
            boolean nowMuted = current.masterGain() > 0.0;
            if (nowMuted) {
                cueBusPreMuteGain.put(bus.id(), current.masterGain());
                project.getCueBusManager().replace(current.withMasterGain(0.0));
                masterFader.setValue(0.0);
            } else {
                double restore = cueBusPreMuteGain.getOrDefault(bus.id(), 1.0);
                project.getCueBusManager().replace(current.withMasterGain(restore));
                masterFader.setValue(restore);
            }
            muteBtn.setStyle(nowMuted
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });
        if (isMuted) {
            muteBtn.setStyle("-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;");
        }

        // "All Pre" — forces every send on this bus to pre-fader (the
        // headphone-mix default during tracking) so engineer fader moves
        // never bleed into the performer's mix. This is a bulk toggle;
        // individual per-send tap choices can still be changed afterwards.
        ToggleButton allPreBtn = new ToggleButton("All Pre");
        allPreBtn.setTooltip(new Tooltip(
                "Force Pre-Fader — set every send on this cue bus to pre-fader"));
        boolean allPre = !bus.sends().isEmpty()
                && bus.sends().stream().allMatch(CueSend::preFader);
        allPreBtn.setSelected(allPre);
        allPreBtn.setOnAction(_ -> {
            CueBus current = project.getCueBusManager().getById(bus.id());
            if (current == null) return;
            CueBus updated = current;
            for (CueSend s : current.sends()) {
                updated = updated.withSend(s.withPreFader(allPreBtn.isSelected()));
            }
            project.getCueBusManager().replace(updated);
            refresh();
        });

        // "Copy main mix" — snapshots the current track-channel faders into
        // this cue bus's sends. Common starting point during tracking; matches
        // the issue's "Copy main mix to cue 1" helper.
        Button copyBtn = new Button("Copy");
        copyBtn.setTooltip(new Tooltip(
                "Copy current main-mix fader/pan values into this cue bus's sends"));
        copyBtn.setOnAction(_ -> copyMainMixToCueBus(bus.id()));

        Button removeBtn = new Button("\u2715");
        removeBtn.getStyleClass().add("track-arm-button");
        removeBtn.setTooltip(new Tooltip("Remove Cue Bus"));
        removeBtn.setOnAction(_ -> {
            cueBusPreMuteGain.remove(bus.id());
            project.getCueBusManager().removeCueBus(bus.id());
            refresh();
        });

        HBox topButtons = new HBox(2, muteBtn, allPreBtn);
        topButtons.setAlignment(Pos.CENTER);
        HBox bottomButtons = new HBox(2, copyBtn, removeBtn);
        bottomButtons.setAlignment(Pos.CENTER);

        Label sendsCount = new Label(bus.sends().size() + " send"
                + (bus.sends().size() == 1 ? "" : "s"));
        sendsCount.getStyleClass().add("mixer-channel-name");
        // Story 266 / §3.2 — "N send(s)" starts with a digit; tabular figures
        // via family-only .numeric-mono so the 9 px channel-strip size below
        // doesn't fight .numeric-caption's 11 px.
        sendsCount.getStyleClass().add("numeric-mono");
        sendsCount.setStyle("-fx-font-size: 9px; -fx-text-fill: #888888;");

        strip.getChildren().addAll(
                kindLabel, nameLabel, outLabel,
                masterFader, sendsCount, topButtons, bottomButtons);
        return strip;
    }

    /**
     * Snapshots every track-channel's current main-mix fader and pan into the
     * cue bus identified by {@code cueBusId}, marking each send as pre-fader.
     * Wraps {@link CueBusManager#copyMainMix(UUID, Map)}.
     */
    void copyMainMixToCueBus(UUID cueBusId) {
        CueBusManager mgr = project.getCueBusManager();
        if (mgr.getById(cueBusId) == null) {
            return;
        }
        Map<UUID, CueBusManager.MainMixLevel> mainMix = new LinkedHashMap<>();
        for (Track t : project.getTracks()) {
            UUID id = parseChannelId(t);
            if (id == null) continue;
            MixerChannel ch = project.getMixerChannelForTrack(t);
            if (ch == null) continue;
            mainMix.put(id, new CueBusManager.MainMixLevel(
                    Math.max(0.0, Math.min(1.0, ch.getVolume())),
                    Math.max(-1.0, Math.min(1.0, ch.getPan()))));
        }
        mgr.copyMainMix(cueBusId, mainMix);
        refresh();
    }

    /**
     * Story 135 — opens a small modal prompt asking for a cue bus name and a
     * hardware stereo-output index (0-based pair index, mapped to physical
     * outputs {@code 2N / 2N+1} per {@link CueBus}). Creates the bus via
     * {@link CueBusManager#createCueBus(String, int)} and refreshes the view.
     *
     * <p>Story 215 — the output-pair picker is now derived from the live
     * driver-reported {@link #outputChannelInfoSupplier} instead of a
     * hard-coded 0..31 spinner. Each entry is labelled with the driver's
     * stem name (e.g. {@code "Phones 1 (Output 7 / 8)"}) and inactive
     * channels are greyed with a {@code "Disabled in driver"} tooltip.
     * When the supplier returns no channels (driver not yet open) the
     * dialog falls back to the legacy {@code 0..31} integer range.</p>
     */
    void promptCreateCueBus() {
        Dialog<javafx.util.Pair<String, Integer>> dialog = new Dialog<>();
        dialog.setTitle("New Cue Bus");
        dialog.setHeaderText("Create a headphone / cue mix bus");

        TextField nameField = new TextField(
                "Cue " + (project.getCueBusManager().getCueBuses().size() + 1));
        nameField.setPrefWidth(200);

        // Story 215 — derive the available pairs from the driver's
        // current output channel list. The supplier returns a fresh
        // snapshot at dialog-open time; live driver-change updates while
        // the dialog is open are out of scope per the story's spec.
        List<AudioChannelInfo> liveOutputs = outputChannelInfoSupplier.get();
        if (liveOutputs == null) {
            liveOutputs = List.of();
        }
        List<CueBusPairOption> pairOptions = buildCueBusOutputPairOptions(
                liveOutputs,
                idx -> project.getCueBusManager().isHardwareOutputInUse(idx));
        if (liveOutputs.isEmpty()
                && LOGGED_EMPTY_OUTPUT_FALLBACK.compareAndSet(false, true)) {
            LOG.log(Level.INFO,
                    "Cue-bus output-pair picker: outputChannelInfoSupplier "
                            + "returned no channels; falling back to legacy "
                            + "0..{0} integer range.",
                    LEGACY_MAX_CUE_BUS_PAIR);
        }

        // Pick the first option whose pair is not already in use.
        CueBusPairOption defaultOption = pairOptions.stream()
                .filter(o -> !project.getCueBusManager().isHardwareOutputInUse(o.pairIndex())
                        && o.active())
                .findFirst()
                .orElse(pairOptions.getFirst());

        ComboBox<CueBusPairOption> outCombo = new ComboBox<>();
        outCombo.getItems().addAll(pairOptions);
        outCombo.setCellFactory(_ -> cueBusPairCell());
        outCombo.setButtonCell(cueBusPairCell());
        outCombo.getSelectionModel().select(defaultOption);
        outCombo.setPrefWidth(220);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Hardware output pair:"), 0, 1);
        grid.add(outCombo, 1, 1);
        Label hint = new Label(
                "Pair index N maps to physical outputs " + "(2N+1) / (2N+2). " +
                        "Each cue bus needs a unique pair.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #aaaaaa;");
        hint.setWrapText(true);
        grid.add(hint, 0, 2, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ThemeManager.getDefault().applyTo(dialog.getDialogPane());

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                String name = nameField.getText() == null || nameField.getText().isBlank()
                        ? "Cue" : nameField.getText().trim();
                CueBusPairOption sel = outCombo.getSelectionModel().getSelectedItem();
                int pair = sel == null ? 0 : Math.max(0, sel.pairIndex());
                return new javafx.util.Pair<>(name, pair);
            }
            return null;
        });

        dialog.showAndWait().ifPresent(pair -> {
            try {
                project.getCueBusManager().createCueBus(pair.getKey(), pair.getValue());
                refresh();
            } catch (IllegalArgumentException ex) {
                Alert err = new Alert(Alert.AlertType.ERROR,
                        "Could not create cue bus: " + ex.getMessage());
                err.showAndWait();
            }
        });
    }

    /**
     * Story 215 — one entry in the cue-bus hardware-output-pair picker.
     *
     * @param pairIndex   zero-based stereo-pair index (output {@code 2N}/{@code 2N+1})
     * @param displayName the label shown in the picker — e.g.
     *                    {@code "Phones 1 (Output 7 / 8)"} when the driver
     *                    reports a stereo stem, otherwise
     *                    {@code "Output 2N+1 / 2N+2"}
     * @param active      {@code false} when the driver reports either
     *                    channel of the pair as disabled — the picker
     *                    greys these entries and tooltips
     *                    {@code "Disabled in driver"}
     */
    record CueBusPairOption(int pairIndex, String displayName, boolean active) {
        CueBusPairOption {
            Objects.requireNonNull(displayName, "displayName must not be null");
            if (pairIndex < 0) {
                throw new IllegalArgumentException(
                        "pairIndex must be >= 0: " + pairIndex);
            }
        }
    }

    /**
     * Story 215 — derives the cue-bus output-pair picker entries from the
     * driver-reported {@link AudioChannelInfo} list.
     *
     * <p>The list is paired up two channels at a time: indices {@code 2N}
     * and {@code 2N+1} form pair {@code N}. When a stereo stem name is
     * recovered via {@link ChannelGrouping#buildOptions(List)} (e.g.
     * {@code "Phones 1"}) it is used as the display label; otherwise the
     * generic {@code "Output 2N+1 / 2N+2"} label is used so the user can
     * still pick the pair. A pair is marked inactive when either channel
     * is reported inactive by the driver.</p>
     *
     * <p>When {@code channels} is empty the legacy {@code 0..LEGACY_MAX_CUE_BUS_PAIR}
     * range is returned so the dialog still opens before the audio
     * device has been initialized for the first time.</p>
     *
     * @param channels       driver-reported output channels in their native
     *                       order; must not be null (may be empty)
     * @param pairInUseTest  predicate consulted to optionally annotate the
     *                       label of pairs already taken by another cue
     *                       bus; may be null to skip the annotation
     * @return picker options in pair-index order; never null, never empty
     */
    static List<CueBusPairOption> buildCueBusOutputPairOptions(
            List<AudioChannelInfo> channels,
            java.util.function.IntPredicate pairInUseTest) {
        Objects.requireNonNull(channels, "channels must not be null");
        if (channels.isEmpty()) {
            List<CueBusPairOption> legacy = new ArrayList<>(LEGACY_MAX_CUE_BUS_PAIR + 1);
            for (int n = 0; n <= LEGACY_MAX_CUE_BUS_PAIR; n++) {
                legacy.add(new CueBusPairOption(
                        n, "Output " + (2 * n + 1) + " / " + (2 * n + 2), true));
            }
            return List.copyOf(legacy);
        }

        // Build a map from firstChannel → stereo stem name for any
        // L/R-paired entries the heuristic recognized.
        Map<Integer, String> stemByFirstChannel = new LinkedHashMap<>();
        for (ChannelGrouping.Option opt : ChannelGrouping.buildOptions(channels)) {
            if (opt.channelCount() == 2) {
                String name = opt.displayName();
                // ChannelGrouping suffixes "(Stereo)"; strip it so the
                // cue-bus label can compose its own "(Output X / Y)" tail.
                String stem = name.endsWith(" (Stereo)")
                        ? name.substring(0, name.length() - " (Stereo)".length())
                        : name;
                stemByFirstChannel.put(opt.firstChannel(), stem);
            }
        }

        // Index channels by their reported zero-based index for O(1) lookup
        // — drivers may not always report channels in strict 0,1,2,... order.
        Map<Integer, AudioChannelInfo> byIndex = new LinkedHashMap<>();
        for (AudioChannelInfo info : channels) {
            byIndex.put(info.index(), info);
        }

        int pairCount = channels.size() / 2;
        List<CueBusPairOption> out = new ArrayList<>(Math.max(1, pairCount));
        for (int n = 0; n < pairCount; n++) {
            AudioChannelInfo a = byIndex.get(2 * n);
            AudioChannelInfo b = byIndex.get(2 * n + 1);
            String label;
            String stem = stemByFirstChannel.get(2 * n);
            if (stem != null) {
                label = stem + " (Output " + (2 * n + 1) + " / " + (2 * n + 2) + ")";
            } else {
                label = "Output " + (2 * n + 1) + " / " + (2 * n + 2);
            }
            if (pairInUseTest != null && pairInUseTest.test(n)) {
                label = label + " — in use";
            }
            boolean active = (a == null || a.active()) && (b == null || b.active());
            out.add(new CueBusPairOption(n, label, active));
        }
        if (out.isEmpty()) {
            // Driver reported a single channel — keep at least pair 0
            // available so the dialog stays usable.
            out.add(new CueBusPairOption(0, "Output 1 / 2", true));
        }
        return List.copyOf(out);
    }

    /**
     * Renders a {@link CueBusPairOption} in the picker. Inactive entries
     * are greyed out and tooltipped with {@code "Disabled in driver"}, the
     * same convention used by {@link #ioCell()} for the per-channel
     * routing dropdowns (story 215).
     */
    private static ListCell<CueBusPairOption> cueBusPairCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(CueBusPairOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setDisable(false);
                    setStyle("");
                    return;
                }
                setText(item.displayName());
                if (!item.active()) {
                    setDisable(true);
                    setStyle("-fx-text-fill: #888888;");
                    setTooltip(new Tooltip("Disabled in driver"));
                } else {
                    setDisable(false);
                    setStyle("");
                    setTooltip(null);
                }
            }
        };
    }

    /**
     * Story 215 — validates every saved {@link CueBus} against the live
     * driver's current output-pair count. Buses whose
     * {@link CueBus#hardwareOutputIndex()} exceeds the device's pair
     * count are added to {@link #disabledCueBusIds} (so their strips
     * render greyed out with an explanatory tooltip) and a single
     * combined warning is emitted via {@code notifier}.
     *
     * <p>One call to this method emits at most one notification — the
     * intent is to surface the problem when a project is loaded onto a
     * machine whose audio device has fewer outputs than the project
     * expects, without spamming the user once per affected bus.</p>
     *
     * @param notifier where to send the user-facing warning; must not be null
     * @return the number of cue buses newly marked as disabled by this call
     */
    public int validateCueBusesAgainstDevice(NotificationManager notifier) {
        Objects.requireNonNull(notifier, "notifier must not be null");
        List<AudioChannelInfo> live = outputChannelInfoSupplier.get();
        if (live == null || live.isEmpty()) {
            // No driver info yet — nothing to validate against. We do not
            // disable any buses on an empty channel list because that would
            // wrongly disable everything before the device finishes opening.
            return 0;
        }
        int pairCount = live.size() / 2;

        // Recompute from scratch so that buses whose pair came back
        // into range (e.g. user switched to a device with more outputs,
        // or reconfigured the bus) are re-enabled.
        disabledCueBusIds.clear();
        // Prune notified-set: IDs no longer stale should be eligible for
        // re-notification if they become stale again later.
        notifiedStaleCueBusIds.retainAll(
                project.getCueBusManager().getCueBuses().stream()
                        .map(CueBus::id).collect(Collectors.toSet()));

        List<CueBus> newlyStale = new ArrayList<>();
        for (CueBus bus : project.getCueBusManager().getCueBuses()) {
            if (bus.hardwareOutputIndex() >= pairCount) {
                disabledCueBusIds.add(bus.id());
                if (notifiedStaleCueBusIds.add(bus.id())) {
                    newlyStale.add(bus);
                }
            }
        }
        if (newlyStale.isEmpty()) {
            return 0;
        }
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < newlyStale.size(); i++) {
            CueBus bus = newlyStale.get(i);
            if (i > 0) {
                msg.append("; ");
            }
            msg.append("Cue bus '").append(bus.label())
                    .append("' was on output pair ")
                    .append(bus.hardwareOutputIndex() + 1)
                    .append("; current device has ")
                    .append(pairCount)
                    .append(" pair").append(pairCount == 1 ? "" : "s")
                    .append(" — bus disabled");
        }
        notifier.notify(msg.toString());
        return newlyStale.size();
    }

    /** Visible for testing — returns an unmodifiable view of the disabled cue-bus IDs. */
    Set<UUID> getDisabledCueBusIds() {
        return java.util.Collections.unmodifiableSet(disabledCueBusIds);
    }

    /** Returns the container holding the cue-bus strips. Visible for testing. */
    HBox getCueBusStrips() {
        return cueBusStrips;
    }

    private VBox buildMasterStrip() {
        MixerChannel master = project.getMixer().getMasterChannel();

        VBox strip = new VBox(4);
        strip.getStyleClass().add("mixer-channel");
        strip.setAlignment(Pos.TOP_CENTER);
        strip.setPrefWidth(CHANNEL_WIDTH);
        strip.setMinWidth(CHANNEL_WIDTH);
        strip.setStyle("-fx-border-color: #7c4dff;");

        Label nameLabel = new Label("Master");
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setStyle("-fx-text-fill: #e040fb; -fx-font-weight: bold;");

        // Story 318 — MASTER_OUT: the post-master-fader, post-mute signal the
        // interface receives. The master strip is built once (constructor) and
        // never rebuilt, so its subscription is created in setMeterFeed(...)
        // rather than here.
        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);
        this.masterMeterDisplay = levelMeter;

        // Story 322 — fader / pan / mute are VM subscribers: bound through a
        // ChannelControlBinder against the registry's master ChannelVM on
        // every refresh() (the strip itself is built once). The master pan is
        // live in the engine since the bus pan law landed.
        Slider volumeFader = new Slider(0.0, 1.0, master.getVolume());
        volumeFader.setOrientation(Orientation.VERTICAL);
        volumeFader.setPrefHeight(FADER_HEIGHT);
        volumeFader.getStyleClass().add("mixer-fader");
        volumeFader.setTooltip(new Tooltip("Master Volume"));

        Slider panSlider = new Slider(-1.0, 1.0, master.getPan());
        panSlider.setPrefWidth(CHANNEL_WIDTH - 12);
        panSlider.getStyleClass().add("mixer-fader");
        panSlider.setTooltip(new Tooltip("Master Pan"));
        Label panLabel = new Label("PAN");
        panLabel.getStyleClass().add("mixer-channel-name");

        Button muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute Master"));
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, CONTROL_ICON_SIZE));

        HBox buttonRow = new HBox(2, muteBtn);
        buttonRow.setAlignment(Pos.CENTER);

        this.masterStripControls = new MixerStripControls(
                volumeFader, panSlider, muteBtn, null, null, null);
        strip.getProperties().put(STRIP_CONTROLS_KEY, masterStripControls);

        // Spacer to align vertically with track strips
        Region spacer = new Region();
        spacer.setPrefHeight(20);

        Node masterIcon = IconNode.of(DawIcon.SPEAKER, CONTROL_ICON_SIZE);

        var format = project.getFormat();
        masterInsertRack = new InsertEffectRack(master, format.channels(), format.sampleRate(),
                format.bufferSize(), undoManager, fxDispatcher);
        masterInsertRack.setPluginRegistry(pluginRegistry);
        masterInsertRack.setMixer(project.getMixer());
        masterInsertRack.setOnOpenEditor(onOpenInsertEditor);
        masterInsertRack.setMeterFeed(meterFeed);
        masterInsertRack.setDragVisualAdvisor(dragVisualAdvisor);
        activeInsertRacks.add(masterInsertRack);

        strip.getChildren().addAll(
                nameLabel, masterIcon, levelMeter, volumeFader,
                panLabel, panSlider, buttonRow, spacer, masterInsertRack);

        return strip;
    }

    private static Node trackTypeIcon(TrackType type) {
        return switch (type) {
            case AUDIO        -> IconNode.of(DawIcon.MICROPHONE, CONTROL_ICON_SIZE);
            case MIDI         -> IconNode.of(DawIcon.PIANO, CONTROL_ICON_SIZE);
            case AUX          -> IconNode.of(DawIcon.MIXER, CONTROL_ICON_SIZE);
            case MASTER       -> IconNode.of(DawIcon.SPEAKER, CONTROL_ICON_SIZE);
            case FOLDER       -> IconNode.of(DawIcon.FOLDER, CONTROL_ICON_SIZE);
            case BED_CHANNEL  -> IconNode.of(DawIcon.SURROUND, CONTROL_ICON_SIZE);
            case AUDIO_OBJECT -> IconNode.of(DawIcon.PAN, CONTROL_ICON_SIZE);
            case REFERENCE    -> IconNode.of(DawIcon.HEADPHONES, CONTROL_ICON_SIZE);
        };
    }

    // ── I/O routing selectors ──────────────────────────────────────────────

    private static final int MAX_IO_CHANNELS = 16;

    /**
     * One row in the I/O routing dropdown, carrying both the underlying
     * routing identity and the rendering metadata (driver-reported
     * display name, kind icon, active flag). Stored as the ComboBox's
     * item type so the list cell factory can render it directly.
     */
    private record IoOption(int firstChannel, int channelCount,
                            String displayName, ChannelKind kind,
                            boolean active, boolean isNoneOrMaster) {
        @Override public String toString() { return displayName; }
    }

    private ComboBox<IoOption> buildInputRoutingSelector(Track track) {
        ComboBox<IoOption> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Input routing"));

        List<IoOption> options = new ArrayList<>();
        // Always offer "None" as the first entry.
        options.add(new IoOption(InputRouting.NONE.firstChannel(),
                InputRouting.NONE.channelCount(),
                InputRouting.NONE.displayName(),
                ChannelKind.Generic.INSTANCE, true, true));

        List<AudioChannelInfo> live = inputChannelInfoSupplier.get();
        if (live != null && !live.isEmpty()) {
            // Driver-reported channels: build options via the L/R-grouping
            // helper so consecutive "Mic 1 L" + "Mic 1 R" auto-collapse to
            // "Mic 1 (Stereo)".
            for (ChannelGrouping.Option opt : ChannelGrouping.buildOptions(live)) {
                options.add(new IoOption(
                        opt.firstChannel(), opt.channelCount(),
                        opt.displayName(), opt.kind(), opt.active(), false));
            }
        } else {
            // Legacy fallback when no live channel info is available.
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch++) {
                options.add(new IoOption(ch, 1, "Input " + (ch + 1),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
                options.add(new IoOption(ch, 2,
                        "Input " + (ch + 1) + "-" + (ch + 2),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
        }

        combo.getItems().addAll(options);
        combo.setCellFactory(_ -> ioCell());
        combo.setButtonCell(ioCell());

        // Select current routing (matched on first channel + count).
        InputRouting current = track.getInputRouting();
        IoOption selected = options.stream()
                .filter(o -> o.firstChannel() == current.firstChannel()
                        && o.channelCount() == current.channelCount())
                .findFirst()
                .orElse(options.getFirst());
        combo.getSelectionModel().select(selected);

        combo.setOnAction(_ -> {
            IoOption opt = combo.getSelectionModel().getSelectedItem();
            if (opt == null) {
                return;
            }
            track.setInputRouting(new InputRouting(opt.firstChannel(), opt.channelCount()));
            track.setInputRoutingDisplayName(opt.isNoneOrMaster() ? "" : opt.displayName());
        });

        return combo;
    }

    private ComboBox<IoOption> buildOutputRoutingSelector(MixerChannel channel) {
        ComboBox<IoOption> combo = new ComboBox<>();
        combo.setMaxWidth(CHANNEL_WIDTH - 8);
        combo.setMaxHeight(18);
        combo.setStyle("-fx-font-size: 8px;");
        combo.setTooltip(new Tooltip("Output routing"));

        List<IoOption> options = new ArrayList<>();
        options.add(new IoOption(OutputRouting.MASTER.firstChannel(),
                OutputRouting.MASTER.channelCount(),
                OutputRouting.MASTER.displayName(),
                ChannelKind.Generic.INSTANCE, true, true));

        List<AudioChannelInfo> live = outputChannelInfoSupplier.get();
        if (live != null && !live.isEmpty()) {
            for (ChannelGrouping.Option opt : ChannelGrouping.buildOptions(live)) {
                options.add(new IoOption(
                        opt.firstChannel(), opt.channelCount(),
                        opt.displayName(), opt.kind(), opt.active(), false));
            }
        } else {
            // Legacy fallback: stereo pairs only, matching the historical UI.
            for (int ch = 0; ch < MAX_IO_CHANNELS; ch += 2) {
                options.add(new IoOption(ch, 2,
                        "Output " + (ch + 1) + "-" + (ch + 2),
                        ChannelKind.Generic.INSTANCE, true, false));
            }
        }

        combo.getItems().addAll(options);
        combo.setCellFactory(_ -> ioCell());
        combo.setButtonCell(ioCell());

        OutputRouting current = channel.getOutputRouting();
        IoOption selected = options.stream()
                .filter(o -> o.firstChannel() == current.firstChannel()
                        && o.channelCount() == current.channelCount())
                .findFirst()
                .orElse(options.getFirst());
        combo.getSelectionModel().select(selected);

        combo.setOnAction(_ -> {
            IoOption opt = combo.getSelectionModel().getSelectedItem();
            if (opt == null) {
                return;
            }
            channel.setOutputRouting(new OutputRouting(opt.firstChannel(), opt.channelCount()));
            channel.setOutputRoutingDisplayName(opt.isNoneOrMaster() ? "" : opt.displayName());
        });

        return combo;
    }

    /**
     * Builds a {@link ListCell} that renders an {@link IoOption} with a
     * small {@link ChannelKind} glyph and dims/disables inactive entries
     * with the "Disabled in driver" tooltip required by story 199.
     */
    private static ListCell<IoOption> ioCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(IoOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    setDisable(false);
                    setStyle("");
                    return;
                }
                setText(item.displayName());
                setGraphic(IconNode.of(iconForKind(item.kind()), 10));
                if (!item.active()) {
                    setDisable(true);
                    setStyle("-fx-text-fill: #888888;");
                    setTooltip(new Tooltip("Disabled in driver"));
                } else {
                    setDisable(false);
                    setStyle("");
                    setTooltip(null);
                }
            }
        };
    }

    private static DawIcon iconForKind(ChannelKind kind) {
        return switch (kind) {
            case ChannelKind.Mic m         -> DawIcon.MICROPHONE;
            case ChannelKind.Line l        -> DawIcon.XLR;
            case ChannelKind.Instrument i  -> DawIcon.GUITAR;
            case ChannelKind.Digital d     -> DawIcon.SPDIF;
            case ChannelKind.Monitor mo    -> DawIcon.MONITOR;
            case ChannelKind.Headphone h   -> DawIcon.HEADPHONES;
            case ChannelKind.Generic g     -> DawIcon.LINK;
        };
    }

    /**
     * Updates a latency label to reflect the current insert-chain latency of
     * the given mixer channel. Called once during strip construction and again
     * each time the {@link InsertEffectRack} rebuilds its slots.
     */
    private static void updateLatencyLabel(Label label, MixerChannel channel, double sampleRate) {
        int latencySamples = channel.getEffectsChain().getTotalLatencySamples();
        if (latencySamples > 0) {
            double latencyMs = latencySamples / sampleRate * 1000.0;
            label.setText(String.format("%.1f ms", latencyMs));
            label.setTooltip(new Tooltip(latencySamples + " samples latency"));
        } else {
            label.setText("");
            label.setTooltip(null);
        }
    }

    /**
     * Renders the solo-safe (solo-in-place defeat) state of a solo button as
     * the {@link #SOLO_SAFE} pseudo-class ring (styles.css
     * {@code .track-solo-button:solo-safe}, tokens not hex) plus the matching
     * tooltip, so the engineer can see at a glance which channels stay
     * audible during a solo. The solo state itself is <em>not</em> painted
     * here (story 322): it is the binder-driven {@code :active} pseudo-class
     * seeded from the VM, never from a click.
     */
    private static void applySoloButtonStyle(Button soloBtn, MixerChannel channel) {
        soloBtn.pseudoClassStateChanged(SOLO_SAFE, channel.isSoloSafe());
        Tooltip tip = new Tooltip(channel.isSoloSafe()
                ? "Solo (Solo Safe enabled — right-click to disable)"
                : "Solo (right-click for Solo Safe)");
        soloBtn.setTooltip(tip);
    }

    /**
     * Installs a right-click context menu on the supplied solo button that
     * toggles the channel's solo-safe flag through {@link SetSoloSafeAction}
     * (so the change participates in undo/redo). Invoked on track and
     * return-bus channel strips.
     *
     * <p>Also registers a sync callback so the button ring and the
     * {@code CheckMenuItem} selection reflect the model after any
     * {@link UndoManager} history change (undo, redo, or recall).</p>
     */
    private void installSoloSafeContextMenu(Button soloBtn, MixerChannel channel) {
        ContextMenu menu = new ContextMenu();
        CheckMenuItem soloSafeItem = new CheckMenuItem("Solo safe");
        soloSafeItem.setSelected(channel.isSoloSafe());
        soloSafeItem.setOnAction(_ -> {
            boolean target = soloSafeItem.isSelected();
            SetSoloSafeAction action = new SetSoloSafeAction(channel, target);
            if (undoManager != null) {
                undoManager.execute(action);
            } else {
                action.execute();
            }
            applySoloButtonStyle(soloBtn, channel);
        });
        menu.getItems().add(soloSafeItem);
        soloBtn.setContextMenu(menu);

        // Keep the button ring + checkmark in sync with the model after
        // undo/redo, snapshot recalls, or "Reset solo safe to defaults".
        soloSafeSyncCallbacks.add(() -> {
            soloSafeItem.setSelected(channel.isSoloSafe());
            applySoloButtonStyle(soloBtn, channel);
        });
    }

    /**
     * The solo-safe ring of a track's {@link MixerChannelStrip} (story 322
     * skin swap): the {@link #SOLO_SAFE} pseudo-class on the strip control —
     * seeded from the model, no skin required — which styles.css paints on the
     * skin's S toggle ({@code .mixer-channel-strip:solo-safe .track-toggle.solo},
     * tokens not hex). The solo state itself is the strip's own {@code :soloed}
     * pseudo-class, mirrored from the VM by the binder.
     */
    private static void applySoloSafeRing(MixerChannelStrip strip, MixerChannel channel) {
        strip.pseudoClassStateChanged(SOLO_SAFE, channel.isSoloSafe());
    }

    private static Tooltip soloTooltip(MixerChannel channel) {
        return new Tooltip(channel.isSoloSafe()
                ? "Solo (Solo Safe enabled — right-click to disable)"
                : "Solo (right-click for Solo Safe)");
    }

    /**
     * The {@link MixerChannelStrip} counterpart of
     * {@link #installSoloSafeContextMenu(Button, MixerChannel)}: the right-click
     * "Solo safe" menu (and the solo tooltip) go on the strip's own S toggle,
     * which lives in its skin. JavaFX creates the skin on the first CSS pass,
     * so the menu is installed on whatever skin is present now and again
     * whenever the skin property changes; the listener is disposed with the
     * strip's bindings. Installing on the toggle (not the strip) keeps the
     * host column's right-click menu (VCA / freeze / presets) reachable from
     * the rest of the strip.
     *
     * <p>Also registers the sync callback that re-reads the model into the
     * ring, the checkmark and the tooltip after any undo-history change.</p>
     */
    private void installSoloSafeContextMenu(MixerChannelStrip strip, MixerChannel channel) {
        ContextMenu menu = new ContextMenu();
        CheckMenuItem soloSafeItem = new CheckMenuItem("Solo safe");
        soloSafeItem.setSelected(channel.isSoloSafe());
        Runnable installOnToggle = () -> {
            if (strip.getSkin() instanceof MixerChannelStripSkin skin) {
                skin.soloButton().setContextMenu(menu);
                skin.soloButton().setTooltip(soloTooltip(channel));
            }
        };
        soloSafeItem.setOnAction(_ -> {
            boolean target = soloSafeItem.isSelected();
            SetSoloSafeAction action = new SetSoloSafeAction(channel, target);
            if (undoManager != null) {
                undoManager.execute(action);
            } else {
                action.execute();
            }
            applySoloSafeRing(strip, channel);
            installOnToggle.run();
        });
        menu.getItems().add(soloSafeItem);

        installOnToggle.run();
        ChangeListener<Skin<?>> skinListener = (_, _, _) -> installOnToggle.run();
        strip.skinProperty().addListener(skinListener);
        stripBindingDisposers.add(() -> strip.skinProperty().removeListener(skinListener));

        soloSafeSyncCallbacks.add(() -> {
            soloSafeItem.setSelected(channel.isSoloSafe());
            applySoloSafeRing(strip, channel);
            installOnToggle.run();
        });
    }

    private void runSoloSafeSyncCallbacks() {
        for (Runnable r : soloSafeSyncCallbacks) {
            r.run();
        }
    }

    /**
     * FX-thread reaction to any {@link UndoManager} history change: re-sync
     * the solo-safe rings and (story 322) heal the {@code Track} mirrors
     * from the channels — an undo / redo of a snapshot recall (the
     * {@link MixerSnapshotsPanel}'s included) or of a channel-only action
     * such as {@code SetVolumeAction} moves the engine's channels without
     * touching their mirrors. No undoable action writes {@code Track}-only
     * volume / pan / mute / solo, so re-asserting the channel state is
     * always the correct direction.
     */
    private void onUndoHistoryChanged() {
        runSoloSafeSyncCallbacks();
        healTrackMirrorsFromChannels();
        reseedSendRows();
    }

    // ── VCA helpers (story 153) ────────────────────────────────────────────

    /**
     * Highlights a channel strip when it is part of the current
     * {@link #selectedChannelIds} multi-selection. Used as a cheap restyle
     * after Ctrl/Shift-click instead of a full {@link #refresh()}.
     */
    private void applyChannelStripSelectionStyle(VBox strip, UUID channelId) {
        if (selectedChannelIds.contains(channelId)) {
            strip.setStyle("-fx-background-color: rgba(124, 77, 255, 0.18);"
                    + " -fx-border-color: #7c4dff; -fx-border-width: 2;");
        } else {
            strip.setStyle("");
        }
    }

    /**
     * Implements the issue's "select several channels → right-click → Create
     * VCA" flow. Prompts for a name, then auto-assigns a palette color and
     * dispatches a {@link CreateVcaGroupAction} (with the seed members)
     * through the {@link UndoManager}. Falls back to the right-clicked
     * channel when nothing is multi-selected.
     */
    private void createVcaFromSelection(UUID rightClickedChannelId) {
        Set<UUID> seeds = new java.util.LinkedHashSet<>(selectedChannelIds);
        if (seeds.isEmpty()) {
            seeds.add(rightClickedChannelId);
        } else if (!seeds.contains(rightClickedChannelId)) {
            // The user right-clicked a strip that isn't in the selection —
            // honor the right-click target as the primary intent and add it
            // to the seeds so it doesn't get silently excluded.
            seeds.add(rightClickedChannelId);
        }

        TextInputDialog nameDialog = new TextInputDialog("VCA " +
                (project.getVcaGroupManager().getVcaGroups().size() + 1));
        nameDialog.setTitle("Create VCA");
        nameDialog.setHeaderText("Name the new VCA group");
        nameDialog.setContentText("Name:");
        Optional<String> result = nameDialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }
        String name = result.get().trim();

        // Auto-pick a palette color so the user gets a visible swatch
        // immediately; they can always change it via the strip's color
        // picker later. Cycling through the 16-color palette mirrors the
        // automatic track-color rotation used by DawProject.
        TrackColor color = TrackColor.fromPaletteIndex(
                project.getVcaGroupManager().getVcaGroups().size());

        CreateVcaGroupAction action = new CreateVcaGroupAction(
                project.getVcaGroupManager(), name, color, new ArrayList<>(seeds));
        if (undoManager != null) {
            undoManager.execute(action);
        } else {
            action.execute();
        }
        selectedChannelIds.clear();
        refresh();
    }

    // ── Channel-link UI (Story 159) ─────────────────────────────────────────

    /**
     * Parses {@code track.getId()} into a {@link UUID}, or returns
     * {@code null} for legacy / hand-crafted test fixtures whose ids are
     * not UUID-formatted (mirrors the same defensive pattern used by the
     * VCA-strip wiring elsewhere in this view).
     */
    private static UUID parseChannelId(Track track) {
        try {
            return UUID.fromString(track.getId());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Inserts a small "link toggle" node between every adjacent pair of
     * channel strips in {@link #channelStrips}. The toggle pairs the two
     * adjacent channels via {@link LinkChannelsAction} (or unpairs via
     * {@link UnlinkChannelsAction}) on left-click and opens a
     * {@link ChannelLinkPopover} on right-click. The toggle itself is a
     * tiny {@link Button} rendered with the {@link DawIcon#LINK} chain
     * glyph; it turns the active accent colour when the pair is linked,
     * matching the behaviour described in Story 159.
     *
     * @param idsInOrder channel ids of the strips in left-to-right order;
     *                   {@code null} entries (non-UUID track ids) get a
     *                   non-clickable placeholder so column spacing is
     *                   preserved
     */
    private void installLinkToggles(List<UUID> idsInOrder) {
        if (idsInOrder.size() < 2) {
            return;
        }
        ChannelLinkManager linkManager = project.getChannelLinkManager();
        // Iterate in reverse so we can splice toggles into channelStrips
        // without invalidating the indices of the strips we still have to
        // process. The toggle for the (i, i+1) pair is inserted at child
        // position i+1.
        for (int i = idsInOrder.size() - 2; i >= 0; i--) {
            UUID leftId  = idsInOrder.get(i);
            UUID rightId = idsInOrder.get(i + 1);
            channelStrips.getChildren().add(i + 1,
                    buildLinkToggle(leftId, rightId, linkManager));
        }
    }

    /**
     * Builds the chain-glyph link toggle node spliced between two adjacent
     * channel strips.
     */
    private Node buildLinkToggle(UUID leftId, UUID rightId, ChannelLinkManager linkManager) {
        ChannelLink existing = (leftId != null && rightId != null)
                ? linkManager.getLink(leftId)
                : null;
        boolean linkedTogether = existing != null
                && existing.involves(leftId)
                && existing.involves(rightId);

        Button btn = new Button();
        btn.getStyleClass().add("mixer-channel-link-toggle");
        btn.setGraphic(IconNode.of(DawIcon.LINK, CONTROL_ICON_SIZE));
        btn.setTooltip(new Tooltip(linkedTogether
                ? "Stereo-link active. Click to unlink. Right-click for link options."
                : "Click to link these two channels into a stereo pair."));
        // Highlight when active so the engineer can see the pair at a glance.
        btn.setStyle(linkedTogether
                ? "-fx-background-color: #00bcd4; -fx-text-fill: #0d0d0d;"
                + " -fx-padding: 2 4 2 4;"
                : "-fx-padding: 2 4 2 4;");
        btn.setUserData(new UUID[]{leftId, rightId});

        // Both endpoints must be UUID-typed and not currently linked to a
        // *third* channel for "Link" to be valid. Disable the button in
        // edge cases so the user gets a clear non-action.
        boolean canLink = leftId != null && rightId != null;
        if (!canLink) {
            btn.setDisable(true);
            return wrapLinkToggle(btn, false, leftId, rightId, null);
        }
        boolean leftLinked  = linkManager.isLinked(leftId);
        boolean rightLinked = linkManager.isLinked(rightId);
        if (!linkedTogether && (leftLinked || rightLinked)) {
            btn.setDisable(true);
            btn.setTooltip(new Tooltip(
                    "One of these channels is already linked to a different channel. "
                            + "Unlink it first."));
        }

        btn.setOnAction(_ -> {
            ChannelLink current = linkManager.getLink(leftId);
            boolean isLinkedTogetherNow = current != null
                    && current.involves(leftId) && current.involves(rightId);
            if (isLinkedTogetherNow) {
                UnlinkChannelsAction unlink = new UnlinkChannelsAction(linkManager, leftId);
                if (undoManager != null) {
                    undoManager.execute(unlink);
                } else {
                    unlink.execute();
                }
            } else if (!linkManager.isLinked(leftId) && !linkManager.isLinked(rightId)) {
                // Default per Story 159: faders + pans + mute/solo on,
                // sends off, RELATIVE mode ("link inserts" removed in 322).
                ChannelLink link = new ChannelLink(leftId, rightId,
                        LinkMode.RELATIVE, true, true, true, false);
                LinkChannelsAction linkAction = new LinkChannelsAction(linkManager, link);
                if (undoManager != null) {
                    undoManager.execute(linkAction);
                } else {
                    linkAction.execute();
                }
            }
        });

        btn.setOnContextMenuRequested(e -> {
            ChannelLink current = linkManager.getLink(leftId);
            if (current != null && current.involves(leftId) && current.involves(rightId)) {
                ChannelLinkPopover popover =
                        new ChannelLinkPopover(linkManager, undoManager, current);
                popover.show(btn, e.getScreenX(), e.getScreenY());
            }
        });

        return wrapLinkToggle(btn, linkedTogether, leftId, rightId, existing);
    }

    /**
     * Wraps the link toggle button in a thin VBox with an optional
     * connector line that spans the two strips at the fader-gap level when
     * the pair is linked. Visible (active accent) only when {@code linked}
     * is true so unlinked pairs render only the unobtrusive toggle.
     */
    private VBox wrapLinkToggle(Button btn, boolean linked,
                                UUID leftId, UUID rightId, ChannelLink link) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(18);
        box.setMinWidth(18);
        box.setMaxWidth(24);
        box.getChildren().add(btn);
        if (linked) {
            // Thin horizontal connector line "between" the two faders.
            Region connector = new Region();
            connector.setPrefSize(20, 2);
            connector.setMinHeight(2);
            connector.setStyle("-fx-background-color: #00bcd4;");
            box.getChildren().add(connector);
        }
        // Tag the wrapper too so tests can locate the node by user data.
        box.setUserData(new LinkTogglePair(leftId, rightId, link));
        return box;
    }

    /**
     * Lookup tag attached to a link-toggle wrapper {@link VBox} so tests
     * (and any future feature using the toggle) can correlate a wrapper
     * node back to the pair of channel ids it spans without walking the
     * scene graph.
     */
    public record LinkTogglePair(UUID leftChannelId, UUID rightChannelId, ChannelLink link) { }

    // ── Story 322: VM bindings — one intent path, both surfaces (§2.10, §5.6) ──

    /**
     * The control handles of a return-bus or master strip built by this view,
     * so the binding code (and a test) can reach the strip's fader / pan /
     * buttons without walking the scene graph. {@code soloBtn}, {@code armBtn}
     * and {@code pannerBtn} are {@code null} on the strips that have no such
     * control (return bus: no arm / 3D; master: mute only). Track strips are
     * {@link MixerChannelStrip}s since story 322 — see {@link TrackStripHandles}.
     *
     * @param volumeFader the linear [0,1] fader; never {@code null}
     * @param panSlider   the [−1,1] pan slider; never {@code null}
     * @param muteBtn     the mute button; never {@code null}
     * @param soloBtn     the solo button, or {@code null} (master)
     * @param armBtn      the arm button, or {@code null} (return bus, master)
     * @param pannerBtn   the 3D-panner button, or {@code null} (return bus, master)
     */
    record MixerStripControls(Slider volumeFader, Slider panSlider, Button muteBtn,
                              Button soloBtn, Button armBtn, Button pannerBtn) {

        MixerStripControls {
            Objects.requireNonNull(volumeFader, "volumeFader must not be null");
            Objects.requireNonNull(panSlider, "panSlider must not be null");
            Objects.requireNonNull(muteBtn, "muteBtn must not be null");
        }

        /** Disables (or re-enables) every control present — the inert state of an unbound strip. */
        void setDisabled(boolean disabled) {
            volumeFader.setDisable(disabled);
            panSlider.setDisable(disabled);
            muteBtn.setDisable(disabled);
            if (soloBtn != null) {
                soloBtn.setDisable(disabled);
            }
            if (armBtn != null) {
                armBtn.setDisable(disabled);
            }
        }
    }

    /**
     * A per-return send row's reflectable widgets ("Link Sends"): the level
     * slider and the tap glyph. {@link #reflect(Send)} pushes the channel's
     * <em>model</em> send into them — after a mirrored write on the partner,
     * and on every history event for every row; the slider listener's echo
     * guard (a value already equal to the model raises nothing) keeps the
     * reflection from re-mirroring or re-writing.
     *
     * @param slider     the send-level slider
     * @param tapButton  the tap-point cycler
     * @param refreshTap re-reads the model send's tap into {@code tapButton}
     */
    record SendRow(Slider slider, Button tapButton, Runnable refreshTap) {

        SendRow {
            Objects.requireNonNull(slider, "slider must not be null");
            Objects.requireNonNull(tapButton, "tapButton must not be null");
            Objects.requireNonNull(refreshTap, "refreshTap must not be null");
        }

        /**
         * Reflects {@code send}, the channel's model send feeding this row's
         * bus; {@code null} means the send does not exist (an undone creation)
         * and the slider shows the bottom of its travel — which the listener
         * treats as "no send, nothing to create", never as a write.
         */
        void reflect(Send send) {
            double level = send != null ? send.getLevel() : 0.0;
            if (slider.getValue() != level) {
                slider.setValue(level);
            }
            refreshTap.run();
        }
    }

    /**
     * A send's pre-gesture state — existence, level and tap — captured when a
     * drag starts and put back on release so the undo entry's
     * {@code execute()} sees the model exactly as it was before the gesture.
     * Story 322 fix round 1 (S6): the linked partner's send needs the same
     * treatment as the source's because the per-tick "Link Sends" mirror
     * rewrites it live.
     */
    private record SendState(boolean present, double level, SendTap tap) {

        static final SendState ABSENT = new SendState(false, 0.0, SendTap.POST_FADER);

        static SendState of(Send send) {
            return send == null ? ABSENT : new SendState(true, send.getLevel(), send.getTap());
        }

        /** Puts the send of {@code channel} feeding {@code target} back to this state. */
        void restore(MixerChannel channel, MixerChannel target) {
            Send send = channel.getSendForTarget(target);
            if (!present) {
                if (send != null) {
                    channel.removeSend(send);
                }
            } else if (send != null) {
                send.setLevel(level);
                if (send.getTap() != tap) {
                    send.setTap(tap);
                }
            }
        }
    }

    /**
     * The undoable form of the live "Link Sends" mirror for one partner: a
     * {@link SetSendRoutingAction} (creates the partner's send when absent,
     * else sets its level) followed by a {@link SetSendTapAction} carrying the
     * exact tap — {@link SendMode} cannot express {@code PRE_INSERTS}, so the
     * routing action alone would not reproduce what the mirror wrote. Undone
     * in reverse inside their compound they put the partner's send back
     * exactly (existence, level, tap).
     */
    private static List<UndoableAction> partnerSendSteps(MixerChannel partner, MixerChannel target,
                                                         double level, SendMode mode, SendTap tap) {
        return List.of(new SetSendRoutingAction(partner, target, level, mode),
                new SetSendTapAction(partner, target, tap));
    }

    /** {@code steps} as one history entry: the single step itself, or a compound. */
    private static UndoableAction asOneAction(String description, List<UndoableAction> steps) {
        return steps.size() == 1 ? steps.get(0) : new CompoundUndoableAction(description, steps);
    }

    /**
     * Binds a track's {@link MixerChannelStrip} and its 3D button to the
     * registry's {@link TrackVM} / {@link ChannelVM} through one
     * {@link TrackControlBinder#bindStrip} raising {@code TrackCommand}s into
     * the wiring's sink — the same intent path the arrangement strip drives.
     * Every strip fact is a VM subscription (name, insert list, pan, dB fader,
     * M/S/R, integrated meter): mute / solo / arm styles are seeded at bind
     * time, and a snapshot recall re-seeds the fader / pan structurally (no
     * imperative re-seed). A track the registry has no VMs for (a non-UUID
     * fixture id, or a channel it does not know even after a reconcile)
     * leaves the whole strip disabled and the 3D button hidden — it is never
     * bound to the model directly ({@code bindStrip} needs both VMs).
     *
     * <p>The strip's meter: {@code bindStrip} subscribes the channel's
     * {@code CHANNEL_POST} tap through {@link ChannelVM#bindMeter(Node)} when
     * the VM has a live feed; the pair is recorded in {@link #trackStripMeters}
     * so the view's scene lifecycle releases and re-acquires it exactly like
     * its own {@link LevelMeterDisplay} bindings.</p>
     */
    private void bindTrackStrip(Track track, MixerChannel mixerChannel, UUID channelId,
                                TrackStripHandles handles, TrackControlWiring wiring) {
        MixerChannelStrip strip = handles.strip();
        Button pannerBtn = handles.pannerBtn();
        TrackChannelRegistry registry = wiring.registry();
        TrackVM trackVm = channelId != null ? registry.trackVm(channelId) : null;
        if (trackVm == null && channelId != null) {
            registry.reconcile(); // a TRACKS signal still queued from another thread
            trackVm = registry.trackVm(channelId);
        }
        ChannelVM channelVm = trackVm != null ? registry.channelVm(mixerChannel.getId()) : null;
        if (trackVm == null || channelVm == null) {
            LOG.fine(() -> "No track / channel VM for '" + track.getName()
                    + "' — its mixer strip stays inert");
            strip.setDisable(true);
            pannerBtn.setVisible(false);
            pannerBtn.setManaged(false);
            return;
        }
        strip.setDisable(false);

        TrackControlBinder binder = new TrackControlBinder(
                track, trackVm, mixerChannel, channelVm, undoableRenames(wiring.commandSink()));
        stripBindingDisposers.add(binder::dispose);
        binder.bindStrip(strip);
        stripBindingDisposers.add(gatePannerButton(pannerBtn, channelVm));
        if (channelVm.hasMeterFeed()) {
            trackStripMeters.add(new TrackStripMeter(channelVm, strip));
        }

        // Story 137 — the input-meter column appears (on arm) or disappears
        // (on disarm) immediately, from whichever surface flipped the flag.
        ReadOnlyBooleanProperty armed = trackVm.armedProperty();
        ChangeListener<Boolean> armRefresh = (_, _, _) -> {
            if (inputLevelMonitorRegistry != null) {
                refresh();
            }
        };
        armed.addListener(armRefresh);
        stripBindingDisposers.add(() -> armed.removeListener(armRefresh));
    }

    /**
     * Binds a standalone strip — a return bus or the master — through a
     * {@link ChannelControlBinder} over the registry's {@link ChannelVM}
     * (§5.6 "Master / return pan": live in the engine; the strip's mute /
     * solo raise the channel-targeted commands). A channel the registry does
     * not know even after a reconcile leaves the controls disabled.
     */
    private void bindStandaloneStrip(MixerChannel channel, MixerStripControls controls,
                                     TrackControlWiring wiring) {
        TrackChannelRegistry registry = wiring.registry();
        ChannelVM channelVm = registry.channelVm(channel.getId());
        if (channelVm == null) {
            registry.reconcile();
            channelVm = registry.channelVm(channel.getId());
        }
        if (channelVm == null) {
            LOG.fine(() -> "No channel VM for '" + channel.getName() + "' — its strip stays inert");
            controls.setDisabled(true);
            return;
        }
        controls.setDisabled(false);
        ChannelControlBinder binder = new ChannelControlBinder(channel, channelVm, wiring.commandSink());
        stripBindingDisposers.add(binder::dispose);
        binder.bindFader(controls.volumeFader());
        binder.bindPan(controls.panSlider());
        binder.bindMute(controls.muteBtn());
        if (controls.soloBtn() != null) {
            binder.bindSolo(controls.soloBtn());
        }
    }

    /**
     * Gates the 3D-panner button on {@link ChannelVM#spatialNodePresentProperty()}:
     * {@code visible} and {@code managed} both follow the fact, so a channel
     * without a spatial insert has no dead affordance and no empty slot.
     *
     * @return the disposer that unbinds both properties
     */
    private static Runnable gatePannerButton(Button pannerBtn, ChannelVM channelVm) {
        pannerBtn.visibleProperty().bind(channelVm.spatialNodePresentProperty());
        pannerBtn.managedProperty().bind(channelVm.spatialNodePresentProperty());
        return () -> {
            pannerBtn.visibleProperty().unbind();
            pannerBtn.managedProperty().unbind();
        };
    }

    /** Raises {@code command} into the current wiring's sink — the one intent path (§2.10). */
    private void dispatchIntent(TrackCommand command) {
        getTrackControlWiring().commandSink().accept(command);
    }

    /**
     * Decorates the wiring's sink so a {@link RenameTrackCommand} raised by
     * this view's strip (the skin's inline name editor) is recorded in this
     * view's {@link UndoManager} — story 322 fix round 1 (S4): the
     * arrangement's inline rename has always been undoable, so a mixer-strip
     * rename must be too, and the surface that owns the undo manager is the
     * one to record it (the handler stays a pure VALIDATE → MUTATE → ANNOUNCE
     * path with no history of its own). Every other command passes straight
     * through — mute / solo / volume / pan gestures are not undoable on
     * either surface. Without an undo manager the sink is returned as is.
     *
     * <p>A rename that {@link RenameTrackCommand#changesNothing() changes
     * nothing} — the strip's text differs from the track's name only by
     * surrounding whitespace — goes straight to the sink like any other
     * command (fix round 2): the handler's VALIDATE no-ops and the binder
     * snaps the strip back, and recording it would push a visible
     * do-nothing entry and clear the redo stack. The command's own
     * normalisation decides, so this view never repeats the strip rule.</p>
     *
     * <p>{@link UndoManager#execute} runs the action <em>before</em> pushing
     * it, so a rename the handler refuses (blank) throws out of
     * {@code execute()} before any entry is recorded; the binder catches that
     * and snaps the strip back, exactly as it did against the bare sink.</p>
     */
    private Consumer<TrackCommand> undoableRenames(Consumer<TrackCommand> sink) {
        if (undoManager == null) {
            return sink;
        }
        return command -> {
            if (command instanceof RenameTrackCommand rename && !rename.changesNothing()) {
                undoManager.execute(new RenameTrackAction(rename, sink));
            } else {
                sink.accept(command);
            }
        };
    }

    /**
     * A strip rename as a history entry: {@code execute()} raises the command
     * through the real sink (so redo takes the same intent path as the
     * gesture), {@code undo()} raises a {@link RenameTrackCommand} back to the
     * name the track had when the rename was raised.
     */
    private static final class RenameTrackAction implements UndoableAction {

        private final RenameTrackCommand rename;
        private final Consumer<TrackCommand> sink;
        private final String previousName;

        RenameTrackAction(RenameTrackCommand rename, Consumer<TrackCommand> sink) {
            this.rename = Objects.requireNonNull(rename, "rename must not be null");
            this.sink = Objects.requireNonNull(sink, "sink must not be null");
            this.previousName = rename.track().getName();
        }

        @Override
        public String description() {
            return "Rename Track: " + previousName + " → " + rename.normalizedName();
        }

        @Override
        public void execute() {
            sink.accept(rename);
        }

        @Override
        public void undo() {
            sink.accept(new RenameTrackCommand(rename.track(), previousName));
        }
    }

    /**
     * After a snapshot recall (or its undo / redo) the channels hold the
     * recalled scene but their {@code Track} mirrors do not — a
     * {@code MixerSnapshot} writes channels only. Re-asserts every track
     * channel's volume / pan / mute / solo through the intent path, whose
     * per-surface VALIDATE turns each command into a Track-only heal (the
     * channel already matches, so nothing is announced or mirrored for it).
     * That keeps §2.10's "any mirrored model is updated in the same dual-write,
     * in one place" true for recall without a single direct {@code Track}
     * write here, and it is what lets the {@code TrackVM}-bound mute / solo of
     * both surfaces re-seed structurally on recall (§5.6 "Snapshot / A-B
     * recall"). Standalone channels (return buses, master) have no mirror.
     */
    private void healTrackMirrorsFromChannels() {
        if (disposed) {
            return; // a late history event must not resurrect a standalone wiring
        }
        java.util.function.Consumer<TrackCommand> sink = getTrackControlWiring().commandSink();
        for (Track track : project.getTracks()) {
            MixerChannel channel = project.getMixerChannelForTrack(track);
            if (channel == null) {
                continue;
            }
            sink.accept(new com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand(
                    channel, channel.getVolume()));
            sink.accept(new com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand(
                    channel, channel.getPan()));
            sink.accept(new ToggleMuteCommand(track, channel.isMuted()));
            sink.accept(new ToggleSoloCommand(track, channel.isSolo()));
        }
    }

    /** Runs and clears every strip-binding disposer of the last build. */
    private void disposeStripBindings() {
        for (Runnable disposer : stripBindingDisposers) {
            disposer.run();
        }
        stripBindingDisposers.clear();
    }

    /**
     * "Link Sends" (§5.6 "Stereo link"): mirrors a send edit on
     * {@code sourceId}'s channel to its stereo partner through
     * {@link ChannelLinkManager#applySendChange} — a no-op unless the pair is
     * linked with {@code linkSends} — then reflects the partner's model send
     * on the partner's row. The reflection cannot re-mirror: the partner
     * slider's listener sees a value equal to its model and raises nothing.
     *
     * @param sourceId the edited channel's id, or {@code null} (no link affordance)
     * @param target   the return bus the edited send feeds
     * @param level    the source send's new level
     * @param tap      the source send's new tap point
     */
    private void mirrorSendToPartner(UUID sourceId, MixerChannel target, double level, SendTap tap) {
        if (sourceId == null) {
            return;
        }
        ChannelLinkManager links = project.getChannelLinkManager();
        ChannelLink link = links.getLink(sourceId);
        if (link == null || !link.linkSends()) {
            return;
        }
        MixerChannel partner = channelByChannelId.get(link.partnerOf(sourceId));
        if (partner == null) {
            return;
        }
        links.applySendChange(link, partner, target, level, tap);
        reflectSendRow(partner, target);
    }

    /**
     * The stereo partner whose sends follow {@code sourceId}'s: present only
     * when the pair is linked with {@code linkSends} and the partner's strip
     * is in the last build.
     */
    private Optional<MixerChannel> linkedSendPartner(UUID sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        ChannelLink link = project.getChannelLinkManager().getLink(sourceId);
        if (link == null || !link.linkSends()) {
            return Optional.empty();
        }
        return Optional.ofNullable(channelByChannelId.get(link.partnerOf(sourceId)));
    }

    /** Re-seeds the send row of {@code channel} feeding {@code target} from the model (no row: no-op). */
    private void reflectSendRow(MixerChannel channel, MixerChannel target) {
        Map<MixerChannel, SendRow> rows = sendRowsByChannelId.get(channel.getId());
        SendRow row = rows != null ? rows.get(target) : null;
        if (row != null) {
            row.reflect(channel.getSendForTarget(target));
        }
    }

    /**
     * Re-seeds every send row of the last build from the model. Run on every
     * history event (and on scene re-attach): an undo / redo of a send action
     * — a linked pair's compound included — moves the model sends without a
     * gesture, and the rows are plain sliders rather than VM subscribers (the
     * strip's own send rows are deliberately unfed — see the story note), so
     * they need the imperative re-seed the VM-bound controls do not.
     */
    private void reseedSendRows() {
        for (Map.Entry<UUID, Map<MixerChannel, SendRow>> rowsOfChannel : sendRowsByChannelId.entrySet()) {
            MixerChannel channel = channelByChannelId.get(rowsOfChannel.getKey());
            if (channel == null) {
                continue;
            }
            for (Map.Entry<MixerChannel, SendRow> row : rowsOfChannel.getValue().entrySet()) {
                row.getValue().reflect(channel.getSendForTarget(row.getKey()));
            }
        }
    }

    /**
     * The member badge's composite readout: the group's name and the
     * channel's effective VCA gain across every group it belongs to
     * ({@link VcaGroupManager#effectiveGainDb}) — e.g. {@code "VCA: Drums
     * (-6.0 dB)"}; a group at {@link VcaGroup#MIN_GAIN_DB} reads as −∞.
     */
    private static String vcaBadgeText(VcaGroupManager vcaMgr, VcaGroup group, UUID channelId) {
        double db = vcaMgr.effectiveGainDb(channelId);
        String gain = db <= VcaGroup.MIN_GAIN_DB
                ? "-∞ dB"
                : String.format(Locale.ROOT, "%+.1f dB", db);
        return "VCA: " + group.label() + " (" + gain + ")";
    }

    /**
     * A track strip built by this view (story 322): the bound
     * {@link MixerChannelStrip} and the host's gated 3D-panner button beside
     * it. Stored on the host column under {@link #TRACK_STRIP_KEY}; the
     * strip's own properties ({@code faderDb}, {@code pan}, {@code muted} /
     * {@code soloed} / {@code armed}, {@code inserts}, {@code meterPeakDb}) are
     * the test seams for what the old {@code MixerStripControls} exposed.
     *
     * @param strip     the bound channel strip; never {@code null}
     * @param pannerBtn the 3D-panner button; never {@code null}
     */
    record TrackStripHandles(MixerChannelStrip strip, Button pannerBtn) {

        TrackStripHandles {
            Objects.requireNonNull(strip, "strip must not be null");
            Objects.requireNonNull(pannerBtn, "pannerBtn must not be null");
        }
    }

    /**
     * The controls of a return / master strip built by this view, or
     * {@code null} for any other node. Package-visible for tests.
     */
    static MixerStripControls controlsOf(Node strip) {
        return strip.getProperties().get(STRIP_CONTROLS_KEY) instanceof MixerStripControls c ? c : null;
    }

    /**
     * The track strip a host column built by this view carries, or
     * {@code null} for any other node (a link toggle, a return strip …).
     * Package-visible for tests.
     */
    static TrackStripHandles trackStripOf(Node hostColumn) {
        return hostColumn.getProperties().get(TRACK_STRIP_KEY) instanceof TrackStripHandles h ? h : null;
    }

    /** The track strips of the last refresh, in project order. Visible for testing. */
    List<TrackStripHandles> getTrackStrips() {
        List<TrackStripHandles> out = new ArrayList<>();
        for (Node n : channelStrips.getChildren()) {
            TrackStripHandles h = trackStripOf(n);
            if (h != null) {
                out.add(h);
            }
        }
        return List.copyOf(out);
    }

    /** The return-bus strips' controls of the last refresh, in bus order. Visible for testing. */
    List<MixerStripControls> getReturnStripControls() {
        List<MixerStripControls> out = new ArrayList<>();
        for (Node n : returnBusStrips.getChildren()) {
            MixerStripControls c = controlsOf(n);
            if (c != null) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** The master strip's controls. Visible for testing. */
    MixerStripControls getMasterStripControls() {
        return masterStripControls;
    }

    /**
     * The send row of the track strip {@code channelId} feeding
     * {@code target}, or {@code null} if that strip / return bus is not in
     * the last build. Visible for testing.
     */
    SendRow getSendRow(UUID channelId, MixerChannel target) {
        Map<MixerChannel, SendRow> rows = sendRowsByChannelId.get(channelId);
        return rows != null ? rows.get(target) : null;
    }
}
