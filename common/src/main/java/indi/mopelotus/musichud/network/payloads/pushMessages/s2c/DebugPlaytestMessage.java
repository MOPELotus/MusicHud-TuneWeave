package indi.mopelotus.musichud.network.payloads.pushMessages.s2c;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.NetworkReceiver;
import indi.mopelotus.musichud.network.payloads.S2CPayload;

public record DebugPlaytestMessage(String identifier, FormatType declaredFormat, boolean stop) implements S2CPayload {
    private static final java.util.concurrent.atomic.AtomicLong localRequests = new java.util.concurrent.atomic.AtomicLong();
    public static int playLocal(String identifier, FormatType format) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return 0;
        String source;
        try { source = indi.mopelotus.musichud.client.audio.PlaytestSource.parse(identifier); }
        catch (IllegalArgumentException error) { feedback("invalidSource"); return 0; }
        long request = localRequests.incrementAndGet();
        Object connection = minecraft.getConnection();
        try {
            NowPlayingInfo.getInstance().stop();
            StreamAudioPlayer.getInstance().playDirectAsync(source, format, null).whenComplete((started, error) -> {
                if (error != null) minecraft.execute(() -> {
                    if (localRequests.get() == request && minecraft.getConnection() == connection) feedback("failed");
                });
            });
            feedback("requested"); return 1;
        } catch (RuntimeException error) { feedback("failed"); return 0; }
    }
    public static int stopLocal() {
        localRequests.incrementAndGet(); NowPlayingInfo.getInstance().stop(); StreamAudioPlayer.getInstance().stop();
        feedback("stopped"); return 1;
    }
    private static void feedback(String key) {
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(
                net.minecraft.network.chat.Component.translatable(MusicHud.MOD_ID + ".command.playtest." + key));
    }
    public static final ByteBufCodec<DebugPlaytestMessage> CODEC = ByteBufCodec.composite(
            Codecs.STRING_UTF8,
            DebugPlaytestMessage::identifier,
            Codecs.STRING_UTF8,
            message -> message.declaredFormat().name(),
            Codecs.BOOL,
            DebugPlaytestMessage::stop,
            (identifier, declaredFormatName, stop) -> new DebugPlaytestMessage(identifier, FormatType.fromSerializedName(declaredFormatName), stop)
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            // Consume legacy debug packets without executing them. File playback is initiated
            // exclusively by the local client-command dispatcher, including in singleplayer.
            INetworkRegister.getInstance().autoRegisterPayload(
                    DebugPlaytestMessage.class,
                    CODEC,
                    NetworkReceiver.noop()
            );
        }
    }
}

