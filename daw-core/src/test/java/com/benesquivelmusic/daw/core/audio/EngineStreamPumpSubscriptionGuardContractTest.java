package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 316 re-review (E5) — pins the POST-STORE re-check in
 * {@code EngineStreamPump$InputSubscriber.onSubscribe}.
 *
 * <h2>The guard</h2>
 * <pre>{@code
 * public void onSubscribe(Flow.Subscription subscription) {
 *     if (!running) { cancelQuietly(subscription); return; }   // (1) pre-store
 *     inputSubscription = subscription;                        //     the store
 *     if (!running) { cancelInputSubscription(); return; }     // (2) post-store
 *     subscription.request(Long.MAX_VALUE);
 * }
 * }</pre>
 * <p>Guard (1) is already pinned behaviourally by
 * {@code EngineStreamPumpTest}, which completes {@code pump.stop()} before
 * granting the subscription. Guard (2) covers a DIFFERENT window — a stop
 * landing between the store and the request — and nothing pinned it: deleting
 * it alone left every existing test green.</p>
 *
 * <h2>Why this sentinel is structural rather than behavioural</h2>
 * <p>The interleaving guard (2) exists for cannot be forced deterministically
 * from a test. It requires the first {@code running} read to observe
 * {@code true}, {@code EngineStreamPump#stop()}'s {@code running = false} to
 * land next, and the second read to observe {@code false}. Between the first
 * read and the store, and between the store and the second read, the method
 * body contains NO call a test can intercept: the store is a plain volatile
 * write of the test's own object, and {@code stop()} clears {@code running}
 * as its very first statement, so any interleaving in which the second read
 * sees {@code false} has the {@code running = false} write strictly after the
 * first read. Forcing it would need a production seam that exists only for
 * the test — a package-private hook around the store, or routing the store
 * through an overridable method — and adding one silently is worse than
 * saying so. A repeat-until-it-races stress loop would be the other option,
 * and a test that passes by luck pins nothing.</p>
 *
 * <p>So this asserts the STRUCTURE instead, and is precise about which guard
 * it pins: a read of {@code running} after the store, feeding a call to
 * {@code cancelInputSubscription}. Deleting guard (2) alone fails
 * {@link #onSubscribeReChecksRunningAfterStoringTheSubscription()}; deleting
 * guard (1) alone fails
 * {@link #onSubscribeGatesOnRunningBeforeStoringTheSubscription()} and the
 * existing behavioural test. What it does NOT verify is that the guard's
 * BODY is the right cleanup beyond naming the cancel — a semantic change
 * inside {@code cancelInputSubscription} is that method's own contract.</p>
 */
class EngineStreamPumpSubscriptionGuardContractTest {

    private static final String PUMP =
            "com/benesquivelmusic/daw/core/audio/EngineStreamPump";
    private static final String SUBSCRIBER_CLASS =
            "com.benesquivelmusic.daw.core.audio.EngineStreamPump$InputSubscriber";
    private static final String CALLBACK = "onSubscribe";
    private static final String RUNNING_FIELD = "running";
    private static final String SUBSCRIPTION_FIELD = "inputSubscription";
    private static final String CLEANUP_CALL = "cancelInputSubscription";

    @Test
    void onSubscribeStoresTheSubscriptionExactlyOnce() throws Exception {
        List<Step> steps = onSubscribeSteps();

        assertThat(steps)
                .as("the scan must have found a body — an empty element list would make"
                        + " every assertion below pass for free")
                .isNotEmpty();
        assertThat(steps.stream().filter(Step::isSubscriptionStore).count())
                .as("%s must publish the subscription exactly once; the guards below are"
                        + " positioned relative to that single store", CALLBACK)
                .isEqualTo(1);
    }

    @Test
    void onSubscribeGatesOnRunningBeforeStoringTheSubscription() throws Exception {
        List<Step> steps = onSubscribeSteps();
        int store = indexOfStore(steps);

        assertThat(runningReadsBefore(steps, store))
                .as("the cheap common case: a subscription granted to an already stopped"
                        + " pump must be cancelled without ever being published")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void onSubscribeReChecksRunningAfterStoringTheSubscription() throws Exception {
        List<Step> steps = onSubscribeSteps();
        int store = indexOfStore(steps);

        assertThat(runningReadsAfter(steps, store))
                .as("THE guard this sentinel exists for: a stop landing between the store"
                        + " and the request saw the field still null, so only this thread"
                        + " can clean up. Without the re-check the stopped pump keeps a"
                        + " requested, uncancelled capture subscription filling a bounded"
                        + " queue nobody drains — and the next resume silently doubles"
                        + " the subscribers")
                .isGreaterThanOrEqualTo(1);
        assertThat(cleanupCallsAfter(steps, store))
                .as("the post-store re-check must actually GUARD the cleanup: a bare"
                        + " re-read of running that led nowhere would satisfy the count"
                        + " above while cancelling nothing")
                .isGreaterThanOrEqualTo(1);
    }

    // ── Bytecode plumbing ────────────────────────────────────────────────

    private static List<Step> onSubscribeSteps() throws Exception {
        Class<?> subscriber = Class.forName(SUBSCRIBER_CLASS);
        byte[] bytes = readClassBytes(subscriber);
        assertThat(bytes).as("%s class file must be readable", SUBSCRIBER_CLASS).isNotNull();
        ClassModel model = ClassFile.of().parse(bytes);

        List<Step> steps = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            if (!method.methodName().stringValue().equals(CALLBACK)) {
                continue;
            }
            CodeAttribute code = method.findAttribute(Attributes.code())
                    .map(CodeAttribute.class::cast)
                    .orElse(null);
            if (code == null) {
                continue;
            }
            for (CodeElement element : code) {
                if (element instanceof FieldInstruction field
                        && field.owner().asInternalName().equals(PUMP)) {
                    steps.add(new Step(field.opcode(), field.name().stringValue(), null));
                } else if (element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(PUMP)) {
                    steps.add(new Step(null, null, invoke.name().stringValue()));
                }
            }
        }
        return steps;
    }

    private static int indexOfStore(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).isSubscriptionStore()) {
                return i;
            }
        }
        throw new AssertionError(CALLBACK + " no longer stores " + SUBSCRIPTION_FIELD
                + "; this sentinel's field names are stale");
    }

    private static long runningReadsBefore(List<Step> steps, int store) {
        return steps.subList(0, store).stream().filter(Step::isRunningRead).count();
    }

    private static long runningReadsAfter(List<Step> steps, int store) {
        return steps.subList(store + 1, steps.size()).stream()
                .filter(Step::isRunningRead).count();
    }

    private static long cleanupCallsAfter(List<Step> steps, int store) {
        return steps.subList(store + 1, steps.size()).stream()
                .filter(step -> CLEANUP_CALL.equals(step.invoked())).count();
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** One field access on, or call into, the enclosing pump. */
    private record Step(Opcode opcode, String field, String invoked) {

        boolean isRunningRead() {
            return opcode == Opcode.GETFIELD && RUNNING_FIELD.equals(field);
        }

        boolean isSubscriptionStore() {
            return opcode == Opcode.PUTFIELD && SUBSCRIPTION_FIELD.equals(field);
        }
    }
}
