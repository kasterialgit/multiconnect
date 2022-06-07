package net.earthcomputer.multiconnect.packets.latest;

import net.earthcomputer.multiconnect.ap.Argument;
import net.earthcomputer.multiconnect.ap.Introduce;
import net.earthcomputer.multiconnect.ap.MessageVariant;
import net.earthcomputer.multiconnect.ap.NetworkEnum;
import net.earthcomputer.multiconnect.ap.Polymorphic;
import net.earthcomputer.multiconnect.ap.Type;
import net.earthcomputer.multiconnect.ap.Types;
import net.earthcomputer.multiconnect.api.Protocols;
import net.earthcomputer.multiconnect.packets.CommonTypes;
import net.earthcomputer.multiconnect.packets.SPacketBossBar;

import java.util.UUID;

@MessageVariant
public class SPacketBossBar_Latest implements SPacketBossBar {
    public UUID uuid;
    public SPacketBossBar.Action action;

    @Polymorphic
    @MessageVariant(minVersion = Protocols.V1_13)
    public static abstract class Action implements SPacketBossBar.Action {
        public int action;
    }

    @Polymorphic(intValue = 0)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class AddAction extends Action implements SPacketBossBar.AddAction {
        public CommonTypes.Text title;
        public float health;
        public Color color;
        public Division division;
        @Type(Types.UNSIGNED_BYTE)
        @Introduce(compute = "computeFlags")
        public int flags;

        public static int computeFlags(@Argument("flags") int flags) {
            return (flags | ((flags & 2) << 1)); // copy bit 1 to 2
        }
    }

    @Polymorphic(intValue = 1)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class RemoveAction extends Action implements SPacketBossBar.RemoveAction {}

    @Polymorphic(intValue = 2)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class UpdateHealthAction extends Action implements SPacketBossBar.UpdateHealthAction {
        public float health;
    }

    @Polymorphic(intValue = 3)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class UpdateTitleAction extends Action implements SPacketBossBar.UpdateTitleAction {
        public CommonTypes.Text title;
    }

    @Polymorphic(intValue = 4)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class UpdateStyleAction extends Action implements SPacketBossBar.UpdateStyleAction {
        public Color color;
        public Division division;
    }

    @Polymorphic(intValue = 5)
    @MessageVariant(minVersion = Protocols.V1_13)
    public static class UpdateFlagsAction extends Action implements SPacketBossBar.UpdateFlagsAction {
        @Type(Types.UNSIGNED_BYTE)
        @Introduce(compute = "computeFlags")
        public int flags;

        public static int computeFlags(@Argument("flags") int flags) {
            return (flags | ((flags & 2) << 1)); // copy bit 1 to 2
        }
    }

    @NetworkEnum
    public enum Color {
        PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE
    }

    @NetworkEnum
    public enum Division {
        NONE, SIX_NOTCHES, TEN_NOTCHES, TWELVE_NOTCHES, TWENTY_NOTCHES
    }
}
