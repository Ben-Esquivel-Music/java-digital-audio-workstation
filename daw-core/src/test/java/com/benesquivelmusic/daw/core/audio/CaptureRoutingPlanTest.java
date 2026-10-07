package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureRoutingPlanTest {
    private static final String BACKEND_NAME = "Capture test";
    private static final AudioDeviceInfo UNPROBED = AudioDeviceInfo.unprobed(0, "Interface", "ASIO");
    private static final DeviceId SELECTED = new DeviceId(BACKEND_NAME, UNPROBED.qualifiedName());
    private static final List<CaptureRoutingPlan.Route> SHARED_ROUTES = List.of(
            route("Mono", 0, 1), route("Stereo", 2, 2, UNPROBED), route("Far pair", 6, 2));

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sharedUnprobedDeviceIsQueriedOnceIncludingUnknownCapacity(boolean known) {
        AudioBackend backend = backend(SELECTED);
        when(backend.inputChannelCapacity(SELECTED)).thenReturn(known ? OptionalInt.of(8) : OptionalInt.empty());

        CaptureRoutingPlan plan = resolve(backend, SHARED_ROUTES, List.of(UNPROBED), true);

        verify(backend).inputChannelCapacity(SELECTED);
        assertThat(plan.sources()).singleElement().satisfies(source -> {
            assertThat(source.device()).isEqualTo(SELECTED);
            assertThat(source.requestedChannels()).isEqualTo(8);
        });
        assertThat(plan.trackSources().values()).containsOnly(0);
        assertThat(plan.widthValidatedTracks()).containsExactlyInAnyOrderElementsOf(
                known ? SHARED_ROUTES.stream().map(CaptureRoutingPlan.Route::id).toList() : List.of());
    }

    @Test
    void laterRoutesAreRefusedUsingTheSameProbedCapacity() {
        AudioBackend backend = backend(SELECTED);
        when(backend.inputChannelCapacity(SELECTED)).thenReturn(OptionalInt.of(2), OptionalInt.of(8));

        assertThatThrownBy(() -> resolve(backend, SHARED_ROUTES, List.of(UNPROBED), true))
                .isInstanceOf(AudioBackendException.class).hasMessageContainingAll("Stereo", "2 input channels");

        verify(backend).inputChannelCapacity(SELECTED);
    }

    @Test
    void capacityCacheExpiresAfterEachResolution() {
        AudioBackend backend = backend(SELECTED);
        when(backend.inputChannelCapacity(SELECTED))
                .thenReturn(OptionalInt.empty(), OptionalInt.of(8), OptionalInt.of(2));

        assertThat(resolve(backend, SHARED_ROUTES, List.of(UNPROBED), true).widthValidatedTracks()).isEmpty();
        assertThat(resolve(backend, SHARED_ROUTES, List.of(UNPROBED), true).widthValidatedTracks()).hasSize(3);
        assertThatThrownBy(() -> resolve(backend, SHARED_ROUTES, List.of(UNPROBED), true))
                .isInstanceOf(AudioBackendException.class).hasMessageContainingAll("Stereo", "2 input channels");

        verify(backend, times(3)).inputChannelCapacity(SELECTED);
    }

    @Test
    void unionOnlyValidationDoesNotProbeAnUnprobedDevice() {
        AudioBackend backend = backend(SELECTED);

        CaptureRoutingPlan plan = resolve(backend, SHARED_ROUTES, List.of(UNPROBED), false);

        assertThat(plan.widthValidatedTracks()).isEmpty();
        verify(backend, never()).inputChannelCapacity(any());
    }

    @Test
    void enumeratedCapacityDoesNotNeedADriverProbe() {
        AudioBackend backend = backend(SELECTED);
        AudioDeviceInfo known = info(0, "ASIO");

        assertThat(resolve(backend, SHARED_ROUTES, List.of(known), true).widthValidatedTracks()).hasSize(3);

        verify(backend, never()).inputChannelCapacity(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void duplicateNamesNeverBecomeCalibrationAliasesRegardlessOfEnumerationOrder(boolean reversed) {
        AudioDeviceInfo mme = info(0, "MME"), wasapi = info(1, "WASAPI");
        List<AudioDeviceInfo> devices = reversed ? List.of(wasapi, mme) : List.of(mme, wasapi);
        List<CaptureRoutingPlan.Route> routes = List.of(route("MME", 0, 1, mme), route("WASAPI", 0, 1, wasapi));
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, mme.qualifiedName()));

        CaptureRoutingPlan plan = resolve(backend, routes, devices, true);

        assertThat(plan.sources()).hasSize(2).allSatisfy(source -> {
            assertThat(source.device().name()).isIn(mme.qualifiedName(), wasapi.qualifiedName());
            assertThat(source.selectionLabels()).containsOnly(source.device().name());
        });
        assertThat(plan.sources().get(plan.trackSources().get("MME")).device().name()).isEqualTo(mme.qualifiedName());
        assertThat(plan.sources().get(plan.trackSources().get("WASAPI")).device().name()).isEqualTo(wasapi.qualifiedName());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedBareDeviceDoesNotBypassDuplicateNameAliasFiltering(boolean reversed) {
        AudioDeviceInfo mme = info(0, "MME"), wasapi = info(1, "WASAPI");
        List<AudioDeviceInfo> devices = reversed ? List.of(wasapi, mme) : List.of(mme, wasapi);
        DeviceId bareSelection = new DeviceId(BACKEND_NAME, mme.name());
        AudioBackend backend = backend(bareSelection);

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Selected", 0, 1)), devices, true);

        assertThat(plan.sources()).singleElement().satisfies(source -> {
            assertThat(source.device()).isEqualTo(bareSelection);
            assertThat(source.selectionLabels()).containsOnly(devices.getFirst().qualifiedName());
        });
    }

    @Test
    void unqualifiedHostApiDoesNotReintroduceADuplicateBareAlias() {
        AudioDeviceInfo unqualified = info(0, ""), qualified = info(1, "MME");
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, qualified.qualifiedName()));

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Unqualified", 0, 1, unqualified)),
                List.of(unqualified, qualified), true);

        assertThat(plan.sources()).singleElement().satisfies(source -> {
            assertThat(source.device().name()).isEqualTo(unqualified.name());
            assertThat(source.selectionLabels()).doesNotContain(unqualified.name());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void qualifiedIdentitySurvivesOtherDevicesWithTheSameLiteralBracketName(boolean reversed) {
        AudioDeviceInfo selected = info(0, "MME");
        AudioDeviceInfo duplicateName = new AudioDeviceInfo(1, selected.qualifiedName(), "WASAPI",
                8, 2, 48_000, List.of(), 0, 0);
        AudioDeviceInfo otherDuplicateName = new AudioDeviceInfo(2, selected.qualifiedName(), "ASIO",
                8, 2, 48_000, List.of(), 0, 0);
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, selected.qualifiedName()));

        List<AudioDeviceInfo> devices = reversed ? List.of(otherDuplicateName, duplicateName, selected)
                : List.of(selected, duplicateName, otherDuplicateName);
        CaptureRoutingPlan plan = resolve(backend, List.of(route("Selected", 0, 1)), devices, true);

        assertThat(plan.sources()).singleElement().satisfies(source ->
                assertThat(source.selectionLabels()).contains(selected.qualifiedName(), selected.name()));
    }

    @Test
    void uniqueNamesAndResolvedDefaultSelectionsRemainCalibrationAliases() {
        AudioDeviceInfo device = info(0, "MME");
        for (DeviceId selection : List.of(new DeviceId(BACKEND_NAME, device.name()), DeviceId.defaultFor(BACKEND_NAME))) {
            AudioBackend backend = backend(selection);

            CaptureRoutingPlan plan = resolve(backend, List.of(route("Default", 0, 1)), List.of(device), true);

            assertThat(plan.sources()).singleElement().satisfies(source -> {
                assertThat(source.device()).isEqualTo(selection.isDefault()
                        ? new DeviceId(BACKEND_NAME, device.qualifiedName()) : selection);
                assertThat(source.selectionLabels()).contains(device.qualifiedName(), device.name());
                if (selection.isDefault()) assertThat(source.selectionLabels()).contains(selection.name());
            });
        }
    }

    @Test
    void identityResolvesTheSameDeviceAfterTheEnumerationIsReordered() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        DeviceId chosen = new DeviceId(BACKEND_NAME, b.qualifiedName());
        Track track = armedTrack("Vocal", 0, 1);
        track.setInputDevice(Optional.of(chosen));
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, a.qualifiedName()));
        BackendStreamRung rung = new BackendStreamRung(backend, SELECTED);

        CaptureRoutingPlan atArm = CaptureRoutingPlan.resolve(rung, List.of(track), List.of(a, b), true);
        // Re-enumerated after a hot-plug: same endpoints, swapped positions and indices.
        CaptureRoutingPlan atStart = CaptureRoutingPlan.resolve(rung, List.of(track),
                List.of(named(0, "Interface B"), named(1, "Interface A")), true);

        for (CaptureRoutingPlan plan : List.of(atArm, atStart)) {
            assertThat(plan.sources().get(plan.sourceFor(track)).device()).isEqualTo(chosen);
        }
    }

    @Test
    void identityWhoseDeviceDisappearedIsRefusedNotReplacedByTheDeviceNowAtItsIndex() {
        DeviceId vanished = new DeviceId(BACKEND_NAME, named(1, "Interface B").qualifiedName());
        Track track = armedTrack("Vocal", 0, 1);
        track.setInputDevice(Optional.of(vanished));
        AudioBackend backend = backend(SELECTED);
        // "Interface C" now occupies the index Interface B used to have.
        List<AudioDeviceInfo> devices = List.of(named(0, "Interface A"), named(1, "Interface C"));

        assertThatThrownBy(() -> CaptureRoutingPlan.resolve(new BackendStreamRung(backend, SELECTED),
                List.of(track), devices, true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Track 'Vocal'")
                .hasMessageContaining("device '" + vanished.name() + "'")
                .hasMessageContaining("unavailable")
                .hasMessageNotContaining("Interface C");
    }

    @Test
    void identityFromAnotherBackendIsRefusedEvenWhenTheNameIsEnumerated() {
        AudioDeviceInfo device = named(0, "Interface A");
        Track track = armedTrack("Vocal", 0, 1);
        track.setInputDevice(Optional.of(new DeviceId("Other backend", device.qualifiedName())));

        assertThatThrownBy(() -> CaptureRoutingPlan.resolve(new BackendStreamRung(backend(SELECTED), SELECTED),
                List.of(track), List.of(device), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Track 'Vocal'")
                .hasMessageContaining("Other backend")
                .hasMessageContaining(BACKEND_NAME);
    }

    @Test
    void ambiguousIdentityIsRefusedRatherThanPickingTheFirstMatch() {
        AudioDeviceInfo first = named(0, "Twin"), second = named(1, "Twin");
        Track track = armedTrack("Vocal", 0, 1);
        track.setInputDevice(Optional.of(new DeviceId(BACKEND_NAME, first.qualifiedName())));

        assertThatThrownBy(() -> CaptureRoutingPlan.resolve(new BackendStreamRung(backend(SELECTED), SELECTED),
                List.of(track), List.of(first, second), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("Track 'Vocal'")
                .hasMessageContaining("ambiguous");
    }

    @Test
    void hintOnlyRouteRecordsFromTheSessionDefaultWhateverDeviceSitsAtTheHintedPosition() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        DeviceId sessionDefault = new DeviceId(BACKEND_NAME, a.qualifiedName());
        Track hinted = armedTrack("Old take", 0, 1);
        hinted.setLegacyInputDeviceIndexHint(1);
        Track noDeviceClaim = armedTrack("Session", 0, 1);

        // A hint is not a device claim, so the engine walks the default ladder for it.
        assertThat(CaptureRoutingPlan.snapshot(List.of(hinted)).getFirst().hasExplicitDevice()).isFalse();
        // Index 1 holds a DIFFERENT device than the session default, then the default itself
        // (re-enumerated in swapped order), then nothing at all.
        List<AudioDeviceInfo> swapped = List.of(named(0, "Interface B"), named(1, "Interface A"));
        for (List<AudioDeviceInfo> devices : List.of(List.of(a, b), swapped, List.of(a))) {
            for (boolean checkWidths : new boolean[]{true, false}) {
                CaptureRoutingPlan plan = CaptureRoutingPlan.resolve(new BackendStreamRung(backend(sessionDefault), sessionDefault),
                        List.of(noDeviceClaim, hinted), devices, checkWidths);

                assertThat(plan.sources()).singleElement()
                        .satisfies(source -> assertThat(source.device()).isEqualTo(sessionDefault));
                assertThat(plan.sourceFor(hinted)).isEqualTo(plan.sourceFor(noDeviceClaim));
            }
        }
    }

    @Test
    void routeWithNeitherIdentityNorHintStillUsesTheSessionDefault() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        DeviceId sessionDefault = new DeviceId(BACKEND_NAME, b.qualifiedName());
        Track noDeviceClaim = armedTrack("Session", 0, 1);

        CaptureRoutingPlan plan = CaptureRoutingPlan.resolve(new BackendStreamRung(backend(sessionDefault), sessionDefault),
                List.of(noDeviceClaim), List.of(a, b), true);

        assertThat(plan.sources().get(plan.sourceFor(noDeviceClaim)).device()).isEqualTo(sessionDefault);
    }

    @Test
    void devicesOnSeparateClocksAreRefusedNamingTheTrackAndTheDevice() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        DeviceId primary = new DeviceId(BACKEND_NAME, a.qualifiedName());
        DeviceId sibling = new DeviceId(BACKEND_NAME, b.qualifiedName());
        AudioBackend backend = backend(primary);
        when(backend.sharesClockDomain(any(), any())).thenReturn(false);
        List<CaptureRoutingPlan.Route> routes = List.of(route("Kick", 0, 1, a), route("Room", 0, 2, b));

        for (boolean checkWidths : new boolean[]{true, false}) {
            assertThatThrownBy(() -> resolve(backend, routes, List.of(a, b), checkWidths))
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContainingAll("Track 'Room'", "device '" + sibling.name() + "'",
                            "not clock-synchronized", primary.name());
        }
        verify(backend, times(2)).sharesClockDomain(primary, sibling);
    }

    @Test
    void defaultAliasResolvedToTheOnlyCapturingDeviceIsOneSourceWithAnExplicitRouteToIt() {
        AudioDeviceInfo mic = named(0, "Interface A");
        AudioDeviceInfo speakers = new AudioDeviceInfo(1, "Speakers", "WASAPI", 0, 2, 48_000, List.of(), 0, 0);
        AudioBackend backend = backend(DeviceId.defaultFor(BACKEND_NAME));
        when(backend.sharesClockDomain(any(), any())).thenReturn(false);
        List<CaptureRoutingPlan.Route> routes = List.of(route("Default", 0, 1), route("Explicit", 2, 2, mic));

        CaptureRoutingPlan plan = resolve(backend, routes, List.of(mic, speakers), true);

        assertThat(plan.sources()).singleElement().satisfies(source -> {
            assertThat(source.device()).isEqualTo(new DeviceId(BACKEND_NAME, mic.qualifiedName()));
            assertThat(source.requestedChannels()).isEqualTo(4);
        });
        verify(backend, never()).sharesClockDomain(any(), any());
    }

    @Test
    void defaultResolvedToOneDeviceAndAnExplicitUnclockedDeviceAreRefusedNamingBothRealDevices() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, a.qualifiedName()));
        when(backend.sharesClockDomain(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1), route("Room", 0, 2, b)), List.of(a, b), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Room'", "device '" + b.qualifiedName() + "'",
                        "not clock-synchronized", "device '" + a.qualifiedName() + "'")
                .hasMessageNotContaining("<default>");
    }

    @Test
    void anUnresolvableDefaultAliasIsNeverNamedAsADeviceInARefusal() {
        AudioDeviceInfo a = named(0, "Interface A"), b = named(1, "Interface B");
        AudioBackend backend = backend(DeviceId.defaultFor(BACKEND_NAME));
        when(backend.sharesClockDomain(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1), route("Room", 0, 2, b)), List.of(a, b), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Room'", "device '" + b.qualifiedName() + "'", "not clock-synchronized",
                        "default input of '" + BACKEND_NAME + "'", "does not resolve to a specific device")
                .hasMessageNotContaining("<default>");
        assertThatThrownBy(() -> resolve(backend, List.of(route("Room", 0, 2, b), route("Kick", 0, 1)), List.of(a, b), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Kick', the default input of '" + BACKEND_NAME + "'", "device '" + b.qualifiedName() + "'")
                .hasMessageNotContaining("<default>");
    }

    @Test
    void aSingleDeviceUnionNeverAsksForAClockDomain() {
        AudioBackend backend = backend(SELECTED);
        when(backend.sharesClockDomain(any(), any())).thenReturn(false);

        assertThat(resolve(backend, SHARED_ROUTES, List.of(info(0, "ASIO")), true).sources()).hasSize(1);

        verify(backend, never()).sharesClockDomain(any(), any());
    }

    @Test
    void anIdentityOnASameNamedSiblingIsNotMergedIntoTheBareSessionSelection() {
        // The bare session label first resolves to the WASAPI endpoint; the track names the MME one.
        AudioDeviceInfo wasapi = info(0, "WASAPI"), mme = info(1, "MME");
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, wasapi.name()));
        List<CaptureRoutingPlan.Route> routes = List.of(route("Session", 0, 1), route("Pinned", 0, 1, mme));

        CaptureRoutingPlan plan = resolve(backend, routes, List.of(wasapi, mme), true);

        assertThat(plan.sources().get(plan.trackSources().get("Pinned")).device())
                .isEqualTo(new DeviceId(BACKEND_NAME, mme.qualifiedName()));
        assertThat(plan.trackSources().get("Pinned")).isNotEqualTo(plan.trackSources().get("Session"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void routeWiderThanAnUnprobedSiblingsProbedCapacityIsRefusedAtPlanTime(boolean selectionProbed) {
        AudioDeviceInfo primary = selectionProbed ? named(0, "Interface A") : AudioDeviceInfo.unprobed(0, "Interface A", "WASAPI");
        AudioDeviceInfo sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        DeviceId primaryId = new DeviceId(BACKEND_NAME, primary.qualifiedName());
        DeviceId siblingId = new DeviceId(BACKEND_NAME, sibling.qualifiedName());
        AudioBackend backend = backend(primaryId);
        when(backend.inputChannelCapacity(primaryId)).thenReturn(OptionalInt.of(8));
        when(backend.inputChannelCapacity(siblingId)).thenReturn(OptionalInt.of(2));

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1), route("Room", 4, 4, sibling)),
                List.of(primary, sibling), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Room'", "device '" + sibling.qualifiedName() + "'", "exceeds 2 input channels");

        verify(backend).inputChannelCapacity(siblingId);
        verify(backend, times(selectionProbed ? 0 : 1)).inputChannelCapacity(primaryId);
    }

    @Test
    void siblingWithinItsProbedCapacityIsAWidthValidatedSecondSource() {
        AudioDeviceInfo primary = named(0, "Interface A"), sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        DeviceId siblingId = new DeviceId(BACKEND_NAME, sibling.qualifiedName());
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        when(backend.inputChannelCapacity(siblingId)).thenReturn(OptionalInt.of(8));

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Kick", 0, 1), route("Room", 4, 4, sibling)),
                List.of(primary, sibling), true);

        assertThat(plan.sources()).hasSize(2);
        assertThat(plan.sources().get(plan.trackSources().get("Room"))).satisfies(source -> {
            assertThat(source.device()).isEqualTo(siblingId);
            assertThat(source.requestedChannels()).isEqualTo(8);
        });
        assertThat(plan.widthValidatedTracks()).containsExactlyInAnyOrder("Kick", "Room");
    }

    @Test
    void siblingWhoseProbeIsUnknownIsNeitherRefusedNorWidthValidated() {
        AudioDeviceInfo primary = named(0, "Interface A"), sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        DeviceId siblingId = new DeviceId(BACKEND_NAME, sibling.qualifiedName());
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        when(backend.inputChannelCapacity(siblingId)).thenReturn(OptionalInt.empty());

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Kick", 0, 1), route("Room", 60, 4, sibling)),
                List.of(primary, sibling), true);

        assertThat(plan.sources()).hasSize(2);
        assertThat(plan.widthValidatedTracks()).containsExactly("Kick");
        verify(backend).inputChannelCapacity(siblingId);
    }

    @Test
    void eachSiblingIsProbedOncePerResolutionAndNeverForUnionOnlyValidation() {
        AudioDeviceInfo primary = named(0, "Interface A"), sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        DeviceId siblingId = new DeviceId(BACKEND_NAME, sibling.qualifiedName());
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        when(backend.inputChannelCapacity(siblingId)).thenReturn(OptionalInt.of(8));
        List<CaptureRoutingPlan.Route> routes = List.of(route("Kick", 0, 1), route("Room L", 0, 1, sibling),
                route("Room R", 1, 1, sibling), route("Overheads", 2, 2, sibling));

        assertThat(resolve(backend, routes, List.of(primary, sibling), false).widthValidatedTracks()).isEmpty();
        verify(backend, never()).inputChannelCapacity(any());

        assertThat(resolve(backend, routes, List.of(primary, sibling), true).widthValidatedTracks()).hasSize(4);
        verify(backend).inputChannelCapacity(siblingId);
        verify(backend, times(1)).inputChannelCapacity(any());
    }

    @Test
    void siblingWhoseCapacityQueryFailsIsRefusedNamingTheTrack() {
        AudioDeviceInfo primary = named(0, "Interface A"), sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        DeviceId siblingId = new DeviceId(BACKEND_NAME, sibling.qualifiedName());
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        IllegalArgumentException gone = new IllegalArgumentException("device 'Interface B' is not available");
        when(backend.inputChannelCapacity(siblingId)).thenThrow(gone);

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1), route("Room", 0, 2, sibling)),
                List.of(primary, sibling), true))
                .isInstanceOf(AudioBackendException.class).hasCause(gone)
                .hasMessageContainingAll("Track 'Room'", "device '" + sibling.qualifiedName() + "'",
                        "input capability query failed: device 'Interface B' is not available");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleDeviceBackendRefusesASiblingRouteWithoutProbingIt(boolean asioSelection) {
        AudioDeviceInfo primary = asioSelection ? new AudioDeviceInfo(0, "Interface A", "ASIO", 8, 2, 48_000, List.of(), 0, 0)
                : named(0, "Interface A");
        AudioDeviceInfo sibling = AudioDeviceInfo.unprobed(1, "Interface B", "WASAPI");
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        // A multi-input backend is still single-device while its selection is an ASIO driver.
        when(backend.supportsMultipleInputDevices()).thenReturn(asioSelection);

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1), route("Room", 0, 2, sibling)),
                List.of(primary, sibling), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Room'", "device '" + sibling.qualifiedName() + "'",
                        "only captures the active device '" + primary.qualifiedName() + "'");

        verify(backend, never()).inputChannelCapacity(any());
    }

    @Test
    void secondAsioDriverIsRefusedByTheOneActiveDriverRuleBeforeItIsProbed() {
        AudioDeviceInfo primary = named(0, "Interface A");
        AudioDeviceInfo first = AudioDeviceInfo.unprobed(1, "Driver A", "ASIO"), second = AudioDeviceInfo.unprobed(2, "Driver B", "ASIO");
        DeviceId firstId = new DeviceId(BACKEND_NAME, first.qualifiedName());
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, primary.qualifiedName()));
        when(backend.inputChannelCapacity(firstId)).thenReturn(OptionalInt.of(8));

        assertThatThrownBy(() -> resolve(backend, List.of(route("Kick", 0, 1, first), route("Room", 0, 2, second)),
                List.of(primary, first, second), true))
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContainingAll("Track 'Room'", "ASIO only allows one active input device '" + first.qualifiedName() + "'");

        verify(backend).inputChannelCapacity(firstId);
        verify(backend, times(1)).inputChannelCapacity(any());
    }

    @Test
    void frozenRouteNoLongerMatchesOnceTheTrackIdentityChanges() {
        Track track = armedTrack("Vocal", 0, 1);
        track.setInputDevice(Optional.of(new DeviceId(BACKEND_NAME, "Interface A [WASAPI]")));
        CaptureRoutingPlan.Route frozen = CaptureRoutingPlan.snapshot(List.of(track)).getFirst();

        assertThat(frozen.matches(track)).isTrue();
        track.setInputDevice(Optional.of(new DeviceId(BACKEND_NAME, "Interface B [WASAPI]")));
        assertThat(frozen.matches(track)).isFalse();
    }

    private static AudioBackend backend(DeviceId selected) {
        AudioBackend backend = mock(AudioBackend.class);
        when(backend.name()).thenReturn(BACKEND_NAME);
        when(backend.selectedInputDevice(any())).thenReturn(selected);
        when(backend.supportsMultipleInputDevices()).thenReturn(true);
        // A word-clocked rig: multi-device fixtures here test union routing, not clock refusal.
        when(backend.sharesClockDomain(any(), any())).thenReturn(true);
        return backend;
    }

    private static CaptureRoutingPlan resolve(AudioBackend backend, List<CaptureRoutingPlan.Route> routes,
                                               List<AudioDeviceInfo> devices, boolean checkWidths) {
        return CaptureRoutingPlan.resolveSnapshots(new BackendStreamRung(backend, SELECTED), routes, devices, checkWidths);
    }

    private static CaptureRoutingPlan.Route route(String name, int first, int count) {
        return new CaptureRoutingPlan.Route(name, name, new InputRouting(first, count), Optional.empty());
    }

    private static CaptureRoutingPlan.Route route(String name, int first, int count, AudioDeviceInfo device) {
        return new CaptureRoutingPlan.Route(name, name, new InputRouting(first, count),
                Optional.of(new DeviceId(BACKEND_NAME, device.qualifiedName())));
    }

    private static Track armedTrack(String name, int first, int count) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setInputRouting(new InputRouting(first, count));
        track.setArmed(true);
        return track;
    }

    private static AudioDeviceInfo named(int index, String name) {
        return new AudioDeviceInfo(index, name, "WASAPI", 8, 2, 48_000, List.of(), 0, 0);
    }

    private static AudioDeviceInfo info(int index, String hostApi) {
        return new AudioDeviceInfo(index, "Interface", hostApi, 8, 2, 48_000, List.of(), 0, 0);
    }
}
