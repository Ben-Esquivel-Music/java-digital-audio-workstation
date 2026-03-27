package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An undoable action that removes a return bus from the mixer.
 *
 * <p>Executing this action removes the return bus and all sends targeting it
 * from every channel. Undoing it restores both the return bus and the sends.</p>
 */
public final class RemoveReturnBusAction implements UndoableAction {

    private final Mixer mixer;
    private final MixerChannel returnBus;
    private final List<ChannelSend> savedSends = new ArrayList<>();

    /**
     * Creates a new remove-return-bus action.
     *
     * @param mixer     the mixer containing the return bus
     * @param returnBus the return bus to remove
     */
    public RemoveReturnBusAction(Mixer mixer, MixerChannel returnBus) {
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.returnBus = Objects.requireNonNull(returnBus, "returnBus must not be null");
    }

    @Override
    public String description() {
        return "Remove Return Bus";
    }

    @Override
    public void execute() {
        // Save sends that target this return bus so they can be restored on undo
        savedSends.clear();
        for (MixerChannel channel : mixer.getChannels()) {
            Send send = channel.getSendForTarget(returnBus);
            if (send != null) {
                savedSends.add(new ChannelSend(channel, send));
            }
        }
        mixer.removeReturnBus(returnBus);
    }

    @Override
    public void undo() {
        mixer.addReturnBus(returnBus);
        for (ChannelSend cs : savedSends) {
            cs.channel.addSend(cs.send);
        }
    }

    /**
     * Pairs a channel with its saved send for undo restoration.
     */
    private record ChannelSend(MixerChannel channel, Send send) {}
}
