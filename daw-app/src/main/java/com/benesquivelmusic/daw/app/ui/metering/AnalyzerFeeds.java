package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** App-scoped owner of dock, utility and inserted analyzer attachments. */
public final class AnalyzerFeeds implements AutoCloseable {
    private final MeteringTapBus bus;
    private final FxDispatcher dispatcher;
    private final Supplier<DawProject> project;
    private final List<AnalyzerBinding> displays = new ArrayList<>();
    private final Map<LiveAnalyzerPlugin, AnalyzerBinding> inserts = new IdentityHashMap<>();
    private final Map<LiveAnalyzerPlugin, MeterTapPoint> hosts = new IdentityHashMap<>();
    private final Runnable removePulse;

    public AnalyzerFeeds(MeteringTapBus bus, FxDispatcher dispatcher, Supplier<DawProject> project) {
        this.bus = bus;
        this.dispatcher = dispatcher;
        this.project = project;
        removePulse = dispatcher.addPulseParticipant(this::reconcileInserts);
    }

    public void bindDisplay(Node surface, AnalyzerProcessor.Kind kind, Consumer<AnalyzerSnapshot> sink) {
        displays.add(new AnalyzerBinding(bus, dispatcher,
                kind == AnalyzerProcessor.Kind.PITCH ? this::tunerPoint : () -> MeterTapPoint.MASTER_CHAIN,
                () -> AnalyzerBinding.isShowing(surface), publish -> new AnalyzerProcessor(kind, publish), sink));
    }

    /** Utility surfaces use MASTER_CHAIN; an inserted instance already owns its host feed. */
    public AnalyzerBinding bindUtility(LiveAnalyzerPlugin plugin, Node surface) {
        return new AnalyzerBinding(bus, dispatcher,
                () -> hosts.containsKey(plugin) ? null : MeterTapPoint.MASTER_CHAIN,
                () -> plugin.isActive() && AnalyzerBinding.isShowing(surface), plugin::createAnalysisConsumer,
                snapshot -> {
                    if (!hosts.containsKey(plugin)) plugin.acceptAnalysis(snapshot);
                }).withConfigurationRevision(plugin::analysisRevision);
    }

    /** First monitored armed track, then first armed track; never guess from the full mix. */
    public MeterTapPoint tunerPoint() {
        DawProject current = project.get();
        if (current == null) return null;
        var armed = current.getTracks().stream().filter(track -> track.isArmed()).toList();
        var selected = armed.stream()
                .filter(track -> track.getInputMonitoringMode() != InputMonitoringMode.OFF)
                .findFirst().or(() -> armed.stream().findFirst());
        return selected.map(current::getMixerChannelForTrack)
                .map(channel -> (MeterTapPoint) new MeterTapPoint.ChannelPost(channel.getId())).orElse(null);
    }

    private void reconcileInserts() {
        hosts.clear();
        DawProject current = project.get();
        if (current != null) {
            var mixer = current.getMixer();
            for (var channel : mixer.getChannels()) collect(channel, new MeterTapPoint.ChannelPost(channel.getId()));
            for (var channel : mixer.getReturnBuses()) collect(channel, new MeterTapPoint.ReturnPost(channel.getId()));
            collect(mixer.getMasterChannel(), MeterTapPoint.MASTER_CHAIN);
        }
        var iterator = inserts.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!hosts.containsKey(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        for (var plugin : hosts.keySet()) {
            inserts.computeIfAbsent(plugin, analyzer -> new AnalyzerBinding(bus, dispatcher,
                    () -> hosts.get(analyzer), analyzer::isActive, analyzer::createAnalysisConsumer, analyzer::acceptAnalysis)
                    .withConfigurationRevision(analyzer::analysisRevision));
        }
    }

    private void collect(MixerChannel channel, MeterTapPoint point) {
        if (channel == null) return;
        for (var slot : channel.getInsertSlots()) {
            if (slot.getPlugin() instanceof LiveAnalyzerPlugin analyzer) hosts.put(analyzer, point);
        }
    }

    @Override
    public void close() {
        removePulse.run();
        displays.forEach(AnalyzerBinding::close);
        inserts.values().forEach(AnalyzerBinding::close);
        displays.clear();
        inserts.clear();
        hosts.clear();
    }
}
