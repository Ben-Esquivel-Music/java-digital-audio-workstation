package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.AnalyzerFeeds;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.binaural.HrtfProfileLibrary;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Activates graph inserts and hosts the focused slot's contract editor. */
final class PluginViewController {
    private static final Logger LOG = Logger.getLogger(PluginViewController.class.getName());

    record Deps(DoubleSupplier sampleRate, IntSupplier bufferSize,
                Supplier<DawProject> project, Runnable markProjectDirty,
                BiConsumer<String, DawIcon> updateStatusBar,
                BiConsumer<NotificationLevel, String> showNotification,
                BiConsumer<List<String>, Node> showEditorInWorkshopPane,
                Supplier<PluginInvocationSupervisor> faultSupervisor,
                Runnable openPluginFaultLog) { }

    record Routing(Supplier<MixerChannel> selectedChannel,
                   Supplier<MixerChannel> chooseChannel,
                   Function<Class<? extends BuiltInDawPlugin>, InsertSlot> createBuiltInSlot,
                   Consumer<MixerChannel> graphChanged) { }

    private final Deps deps;
    private final HrtfProfileLibrary hrtfProfileLibrary = new HrtfProfileLibrary();
    private Routing routing;
    private PluginEditorSession activeEditorSession;
    private MixerChannel activeChannel;
    private InsertSlot activeSlot;
    private AnalyzerFeeds analyzerFeeds;
    private MeterFeed meterFeed;
    private Runnable removeGraphPulse;
    private java.util.concurrent.Executor activationWorker = Runnable::run;
    private Function<DawPlugin, Supplier<InsertSlot>> externalSlotLoader = plugin -> () -> prepareExternalSlot(plugin);
    private final java.util.Set<PendingActivation> pendingActivations = new java.util.HashSet<>();
    private boolean disposed;
    private record PendingActivation(MixerChannel channel, Object pluginKey) { }

    void setActivationWorker(java.util.concurrent.Executor worker) {
        activationWorker = Objects.requireNonNull(worker);
    }

    void setExternalSlotLoader(Function<DawPlugin, Supplier<InsertSlot>> loader) {
        externalSlotLoader = Objects.requireNonNull(loader);
    }

    PluginViewController(Deps deps) {
        this.deps = Objects.requireNonNull(deps);
    }

    void setRouting(Routing routing, FxDispatcher dispatcher) {
        this.routing = Objects.requireNonNull(routing);
        if (removeGraphPulse != null) removeGraphPulse.run();
        removeGraphPulse = dispatcher == null ? null
                : dispatcher.addPulseParticipant(this::reconcileGraph);
    }

    void setAnalyzerFeeds(AnalyzerFeeds feeds) { analyzerFeeds = feeds; }
    void setMeterFeed(MeterFeed feed) { meterFeed = feed; }

    void onManagePlugins(PluginRegistry registry) {
        deps.updateStatusBar().accept("Opening plugin manager...", DawIcon.MENU);
        new PluginManagerDialog(registry).showAndWait();
        deps.updateStatusBar().accept("Plugin manager closed", DawIcon.SETTINGS);
    }

    void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
        try {
            MixerChannel channel = targetChannel();
            if (channel == null) return;
            if (pluginClass == MetronomePlugin.class) {
                if (pendingActivations.stream().anyMatch(pending -> pending.pluginKey() == MetronomePlugin.class)) {
                    return;
                }
                for (MixerChannel candidate : graphChannels()) {
                    for (InsertSlot slot : candidate.getInsertSlots()) {
                        if (slot.getPlugin() instanceof MetronomePlugin) {
                            openSlotEditor(candidate, slot);
                            return;
                        }
                    }
                }
            }
            var activation = new PendingActivation(channel, pluginClass);
            if (focusExistingSlot(activation)) return;
            requireFreeSlot(channel);
            loadForInsertion(activation, pluginClass.getSimpleName(),
                    () -> routing.createBuiltInSlot().apply(pluginClass));
        } catch (RuntimeException | Error failure) {
            activationFailed(pluginClass.getSimpleName(), failure);
        }
    }

    void onActivateExternalPlugin(DawPlugin plugin) {
        Objects.requireNonNull(plugin);
        try {
            MixerChannel channel = targetChannel();
            if (channel == null) return;
            var activation = new PendingActivation(channel, plugin.getDescriptor().id());
            if (focusExistingSlot(activation)) return;
            requireFreeSlot(channel);
            loadForInsertion(activation,
                    plugin.getDescriptor().name(), externalSlotLoader.apply(plugin));
        } catch (RuntimeException | Error failure) {
            activationFailed(plugin.getDescriptor().name(), failure);
        }
    }

    private InsertSlot prepareExternalSlot(DawPlugin plugin) {
        try {
            plugin.initialize(pluginContext());
            InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin)
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.getDescriptor().name() + " does not expose an audio processor"));
            plugin.activate();
            return slot;
        } catch (RuntimeException | Error failure) {
            try { plugin.dispose(); } catch (RuntimeException | Error disposalFailure) { failure.addSuppressed(disposalFailure); }
            throw failure;
        }
    }

    private void loadForInsertion(PendingActivation activation, String label, Supplier<InsertSlot> factory) {
        if (disposed || !pendingActivations.add(activation)) return;
        DawProject requestedProject = deps.project().get();
        activationWorker.execute(() -> {
            try {
                InsertSlot slot = factory.get();
                finishOnFx(() -> {
                    pendingActivations.remove(activation);
                    if (disposed || requestedProject != deps.project().get()
                            || (requestedProject != null && !graphChannels().contains(activation.channel()))) {
                        activationWorker.execute(slot::disposeAfterQuiescence);
                        return;
                    }
                    try {
                        if (focusExistingSlot(activation)) {
                            activationWorker.execute(slot::disposeAfterQuiescence);
                            return;
                        }
                        requireFreeSlot(activation.channel());
                        insertAndOpen(activation.channel(), slot);
                    } catch (RuntimeException | Error failure) {
                        if (!activation.channel().getInsertSlots().contains(slot)) {
                            activationWorker.execute(slot::disposeAfterQuiescence);
                        }
                        activationFailed(label, failure);
                    }
                });
            } catch (RuntimeException | Error failure) {
                finishOnFx(() -> {
                    pendingActivations.remove(activation);
                    if (!disposed) activationFailed(label, failure);
                });
            }
        });
    }

    private static void finishOnFx(Runnable work) {
        if (javafx.application.Platform.isFxApplicationThread()) work.run();
        else FxDispatcher.runOnFx(work);
    }

    private boolean focusExistingSlot(PendingActivation activation) {
        for (InsertSlot slot : activation.channel().getInsertSlots()) {
            boolean matches = activation.pluginKey() instanceof Class<?> type
                    ? matchesBuiltIn(type, slot)
                    : slot.getPlugin() != null
                            && slot.getPlugin().getDescriptor().id().equals(activation.pluginKey());
            if (matches) {
                openSlotEditor(activation.channel(), slot);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesBuiltIn(Class<?> type, InsertSlot slot) {
        if (type.isInstance(slot.getPlugin())) return true;
        BuiltInPlugin metadata = type.getAnnotation(BuiltInPlugin.class);
        return slot.getPlugin() == null && slot.getEffectType() != null && metadata != null
                && metadata.label().equals(slot.getEffectType().getDisplayName());
    }

    private MixerChannel targetChannel() {
        Objects.requireNonNull(routing, "Plugin routing has not been configured");
        MixerChannel selected = routing.selectedChannel().get();
        return selected == null ? routing.chooseChannel().get() : selected;
    }

    private static void requireFreeSlot(MixerChannel channel) {
        if (channel.getInsertCount() >= MixerChannel.MAX_INSERT_SLOTS) {
            throw new IllegalStateException(channel.getName() + " has no free insert slots");
        }
    }

    private void insertAndOpen(MixerChannel channel, InsertSlot slot) {
        channel.addInsert(slot);
        changedAndOpen(channel, slot);
    }

    private void changedAndOpen(MixerChannel channel, InsertSlot slot) {
        deps.markProjectDirty().run();
        routing.graphChanged().accept(channel);
        openSlotEditor(channel, slot);
    }

    private PluginContext pluginContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return deps.sampleRate().getAsDouble(); }
            @Override public int getBufferSize() { return deps.bufferSize().getAsInt(); }
            @Override public void log(String message) { LOG.info(message); }
        };
    }

    void openSlotEditor(MixerChannel channel, InsertSlot slot) {
        if (!channel.getInsertSlots().contains(slot)) {
            throw new IllegalArgumentException("The editor's slot is no longer in the channel");
        }
        if (activeChannel == channel && activeSlot == slot && activeEditorSession != null) {
            focusActiveEditor();
            return;
        }
        closeActiveEditor(false);
        PluginEditorSession session = PluginEditorSession.open(channel, slot,
                new PluginEditorSession.Deps(deps.sampleRate(), deps.faultSupervisor(),
                        deps.openPluginFaultLog(), deps.showNotification()));
        activeChannel = channel;
        activeSlot = slot;
        activeEditorSession = session;
        session.bindAnalyzer(analyzerFeeds);
        if (meterFeed != null) session.bindInsertMeters(meterFeed, slot.getPluginInstanceId());
        session.frame().setOnCloseRequested(() -> {
            if (activeEditorSession == session) closeActiveEditor(true);
        });
        showActiveEditor();
        deps.updateStatusBar().accept("Opened editor for " + slot.getName(), null);
    }

    private void focusActiveEditor() {
        showActiveEditor();
        activeEditorSession.frame().requestFocus();
    }

    private void showActiveEditor() {
        var segments = new ArrayList<String>();
        addSegmentIfPresent(segments, activeChannel.getName());
        addSegmentIfPresent(segments, activeEditorSession.frame().getVendor());
        addSegmentIfPresent(segments, activeEditorSession.frame().getPluginName());
        deps.showEditorInWorkshopPane().accept(segments, activeEditorSession.frame());
    }

    void reconcileGraph() {
        if (activeSlot != null && (!graphChannels().contains(activeChannel)
                || !activeChannel.getInsertSlots().contains(activeSlot))) closeActiveEditor(true);
    }

    private List<MixerChannel> graphChannels() {
        DawProject project = deps.project().get();
        if (project == null) return activeChannel == null ? List.of() : List.of(activeChannel);
        var channels = new ArrayList<>(project.getMixer().getChannels());
        channels.addAll(project.getMixer().getReturnBuses());
        channels.add(project.getMixer().getMasterChannel());
        return channels;
    }

    private void closeActiveEditor(boolean clearPane) {
        if (activeEditorSession != null) activeEditorSession.dispose();
        activeEditorSession = null;
        activeChannel = null;
        activeSlot = null;
        if (clearPane) deps.showEditorInWorkshopPane().accept(List.of(), null);
    }

    void dispose() {
        disposed = true;
        closeActiveEditor(true);
        if (removeGraphPulse != null) removeGraphPulse.run();
        removeGraphPulse = null;
    }

    private void activationFailed(String label, Throwable failure) {
        LOG.log(Level.WARNING, "Failed to activate " + label, failure);
        deps.updateStatusBar().accept("Failed to activate " + label, null);
        deps.showNotification().accept(NotificationLevel.ERROR,
                "Failed to activate " + label + ": " + failure.getMessage());
    }

    private static void addSegmentIfPresent(List<String> segments, String value) {
        if (value != null && !value.isBlank()) segments.add(value);
    }

    PluginEditorSession activeEditorSessionForTest() { return activeEditorSession; }

    public void onManageHrtfProfiles() {
        HrtfProfileBrowserDialog dialog = new HrtfProfileBrowserDialog(
                hrtfProfileLibrary, deps.sampleRate().getAsDouble());
        dialog.showAndWait().ifPresent(selected -> {
            String current = deps.project().get().getActiveHrtfProfileName();
            if (!selected.equals(current)) {
                deps.project().get().setActiveHrtfProfileName(selected);
                deps.markProjectDirty().run();
            }
        });
    }
}
