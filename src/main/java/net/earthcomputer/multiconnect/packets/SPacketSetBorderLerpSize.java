package net.earthcomputer.multiconnect.packets;

import net.earthcomputer.multiconnect.ap.MessageVariant;

@MessageVariant
public class SPacketSetBorderLerpSize {
    public double oldDiameter;
    public double newDiameter;
    public long time;
}