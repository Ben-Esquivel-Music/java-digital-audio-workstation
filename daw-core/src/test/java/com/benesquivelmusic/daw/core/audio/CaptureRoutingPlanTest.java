package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
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
            route("Mono", 0, 1, -1), route("Stereo", 2, 2, 0), route("Far pair", 6, 2, -1));

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
                .isInstanceOf(AudioBackendException.class).hasMessageContaining("Stereo", "2 input channels");

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
                .isInstanceOf(AudioBackendException.class).hasMessageContaining("Stereo", "2 input channels");

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
        List<CaptureRoutingPlan.Route> routes = List.of(route("MME", 0, 1, 0), route("WASAPI", 0, 1, 1));
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

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Selected", 0, 1, -1)), devices, true);

        assertThat(plan.sources()).singleElement().satisfies(source -> {
            assertThat(source.device()).isEqualTo(bareSelection);
            assertThat(source.selectionLabels()).containsOnly(devices.getFirst().qualifiedName());
        });
    }

    @Test
    void unqualifiedHostApiDoesNotReintroduceADuplicateBareAlias() {
        AudioDeviceInfo unqualified = info(0, ""), qualified = info(1, "MME");
        AudioBackend backend = backend(new DeviceId(BACKEND_NAME, qualified.qualifiedName()));

        CaptureRoutingPlan plan = resolve(backend, List.of(route("Unqualified", 0, 1, 0)),
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
        CaptureRoutingPlan plan = resolve(backend, List.of(route("Selected", 0, 1, -1)), devices, true);

        assertThat(plan.sources()).singleElement().satisfies(source ->
                assertThat(source.selectionLabels()).contains(selected.qualifiedName(), selected.name()));
    }

    @Test
    void uniqueNamesAndResolvedDefaultSelectionsRemainCalibrationAliases() {
        AudioDeviceInfo device = info(0, "MME");
        for (DeviceId selection : List.of(new DeviceId(BACKEND_NAME, device.name()), DeviceId.defaultFor(BACKEND_NAME))) {
            AudioBackend backend = backend(selection);

            CaptureRoutingPlan plan = resolve(backend, List.of(route("Default", 0, 1, -1)), List.of(device), true);

            assertThat(plan.sources()).singleElement().satisfies(source -> {
                assertThat(source.device()).isEqualTo(selection.isDefault()
                        ? new DeviceId(BACKEND_NAME, device.qualifiedName()) : selection);
                assertThat(source.selectionLabels()).contains(device.qualifiedName(), device.name());
                if (selection.isDefault()) assertThat(source.selectionLabels()).contains(selection.name());
            });
        }
    }

    private static AudioBackend backend(DeviceId selected) {
        AudioBackend backend = mock(AudioBackend.class);
        when(backend.name()).thenReturn(BACKEND_NAME);
        when(backend.selectedInputDevice(any())).thenReturn(selected);
        when(backend.supportsMultipleInputDevices()).thenReturn(true);
        return backend;
    }

    private static CaptureRoutingPlan resolve(AudioBackend backend, List<CaptureRoutingPlan.Route> routes,
                                               List<AudioDeviceInfo> devices, boolean checkWidths) {
        return CaptureRoutingPlan.resolveSnapshots(new BackendStreamRung(backend, SELECTED), routes, devices, checkWidths);
    }

    private static CaptureRoutingPlan.Route route(String name, int first, int count, int device) {
        return new CaptureRoutingPlan.Route(name, name, new InputRouting(first, count), device);
    }

    private static AudioDeviceInfo info(int index, String hostApi) {
        return new AudioDeviceInfo(index, "Interface", hostApi, 8, 2, 48_000, List.of(), 0, 0);
    }
}
