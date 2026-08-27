package com.benesquivelmusic.daw.core.metering;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MeterTapPointTest {

    @Test
    void masterSingletonsEqualFreshInstances() {
        assertThat(MeterTapPoint.MASTER_CHAIN).isEqualTo(new MeterTapPoint.MasterChain());
        assertThat(MeterTapPoint.MASTER_OUT).isEqualTo(new MeterTapPoint.MasterOut());
        assertThat(MeterTapPoint.MASTER_CHAIN).isNotEqualTo(MeterTapPoint.MASTER_OUT);
    }

    @Test
    void channelPostHasValueSemanticsOnTheChannelId() {
        UUID id = UUID.randomUUID();
        assertThat(new MeterTapPoint.ChannelPost(id))
                .isEqualTo(new MeterTapPoint.ChannelPost(id))
                .hasSameHashCodeAs(new MeterTapPoint.ChannelPost(id))
                .isNotEqualTo(new MeterTapPoint.ChannelPost(UUID.randomUUID()));
    }

    @Test
    void differentKindsWithTheSameIdAreDifferentTapPoints() {
        UUID id = UUID.randomUUID();
        assertThat(new MeterTapPoint.ChannelPost(id))
                .isNotEqualTo(new MeterTapPoint.ReturnPost(id))
                .isNotEqualTo(new MeterTapPoint.InsertIo(id));
    }

    @Test
    void identifiedTapPointsRejectNullIds() {
        assertThatNullPointerException().isThrownBy(() -> new MeterTapPoint.ChannelPost(null));
        assertThatNullPointerException().isThrownBy(() -> new MeterTapPoint.ReturnPost(null));
        assertThatNullPointerException().isThrownBy(() -> new MeterTapPoint.InsertIo(null));
    }

    @Test
    void tapPointsWorkAsMapKeys() {
        UUID id = UUID.randomUUID();
        Map<MeterTapPoint, String> byPoint = new HashMap<>();
        byPoint.put(new MeterTapPoint.ChannelPost(id), "channel");
        byPoint.put(MeterTapPoint.MASTER_OUT, "master-out");
        assertThat(byPoint.get(new MeterTapPoint.ChannelPost(id))).isEqualTo("channel");
        assertThat(byPoint.get(new MeterTapPoint.MasterOut())).isEqualTo("master-out");
        assertThat(byPoint.get(new MeterTapPoint.ReturnPost(id))).isNull();
    }

    @Test
    void sealedHierarchyIsExhaustivelySwitchable() {
        UUID id = UUID.randomUUID();
        assertThat(describe(new MeterTapPoint.ChannelPost(id))).isEqualTo("channel:" + id);
        assertThat(describe(new MeterTapPoint.ReturnPost(id))).isEqualTo("return:" + id);
        assertThat(describe(MeterTapPoint.MASTER_CHAIN)).isEqualTo("master-chain");
        assertThat(describe(MeterTapPoint.MASTER_OUT)).isEqualTo("master-out");
        assertThat(describe(new MeterTapPoint.InsertIo(id))).isEqualTo("insert:" + id);
    }

    private static String describe(MeterTapPoint point) {
        return switch (point) {
            case MeterTapPoint.ChannelPost(UUID channelId) -> "channel:" + channelId;
            case MeterTapPoint.ReturnPost(UUID busId) -> "return:" + busId;
            case MeterTapPoint.MasterChain() -> "master-chain";
            case MeterTapPoint.MasterOut() -> "master-out";
            case MeterTapPoint.InsertIo(UUID pluginInstanceId) -> "insert:" + pluginInstanceId;
        };
    }
}
