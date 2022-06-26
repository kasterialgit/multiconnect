package net.earthcomputer.multiconnect.debug;

import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.earthcomputer.multiconnect.ap.Registries;
import net.earthcomputer.multiconnect.api.ThreadSafe;
import net.earthcomputer.multiconnect.connect.ConnectionMode;
import net.earthcomputer.multiconnect.impl.ConnectionInfo;
import net.earthcomputer.multiconnect.impl.PacketIntrinsics;
import net.earthcomputer.multiconnect.impl.PacketSystem;
import net.earthcomputer.multiconnect.mixin.connect.ClientConnectionAccessor;
import net.earthcomputer.multiconnect.protocols.ProtocolRegistry;
import net.earthcomputer.multiconnect.protocols.generic.AbstractProtocol;
import net.earthcomputer.multiconnect.protocols.generic.Key;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandler;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketEncoderException;
import net.minecraft.state.property.Property;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class DebugUtils {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MULTICONNECT_ISSUES_BASE_URL = "https://github.com/Earthcomputer/multiconnect/issues";
    private static final String MULTICONNECT_ISSUE_URL = MULTICONNECT_ISSUES_BASE_URL + "/%d";
    private static int rareBugIdThatOccurred = 0;
    private static long timeThatRareBugOccurred;
    public static String lastServerBrand = ClientBrandRetriever.VANILLA;
    public static final boolean UNIT_TEST_MODE = Boolean.getBoolean("multiconnect.unitTestMode");
    public static final boolean IGNORE_ERRORS = Boolean.getBoolean("multiconnect.ignoreErrors");
    public static final boolean DUMP_REGISTRIES = Boolean.getBoolean("multiconnect.dumpRegistries");
    public static final boolean SKIP_TRANSLATION = Boolean.getBoolean("multiconnect.skipTranslation");
    public static final boolean STORE_BUFS_FOR_HANDLER = Boolean.getBoolean("multiconnect.storeBufsForHandler");

    public static final Key<byte[]> STORED_BUF = Key.create("storedBuf");
    public static final AttributeKey<byte[]> NETTY_STORED_BUF = AttributeKey.valueOf("multiconnect.storedBuf");
    public static final AttributeKey<Boolean> NETTY_HAS_HANDLED_ERROR = AttributeKey.valueOf("multiconnect.hasHandledError");

    private static final Map<TrackedData<?>, String> TRACKED_DATA_NAMES = new IdentityHashMap<>();
    private static void computeTrackedDataNames() {
        Set<Class<?>> trackedDataHolders = new HashSet<>();
        for (Field field : EntityType.class.getFields()) {
            if (field.getType() == EntityType.class && Modifier.isStatic(field.getModifiers())) {
                if (field.getGenericType() instanceof ParameterizedType type) {
                    if (type.getActualTypeArguments()[0] instanceof Class<?> entityClass && Entity.class.isAssignableFrom(entityClass)) {
                        for (; entityClass != Object.class; entityClass = entityClass.getSuperclass()) {
                            trackedDataHolders.add(entityClass);
                        }
                    }
                }
            }
        }
        for (AbstractProtocol protocol : ProtocolRegistry.all()) {
            trackedDataHolders.add(protocol.getClass());
        }

        for (Class<?> trackedDataHolder : trackedDataHolders) {
            for (Field field : trackedDataHolder.getDeclaredFields()) {
                if (field.getType() == TrackedData.class && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    TrackedData<?> trackedData;
                    try {
                        trackedData = (TrackedData<?>) field.get(null);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                    TRACKED_DATA_NAMES.put(trackedData, trackedDataHolder.getSimpleName() + "::" + field.getName());
                }
            }
        }
    }

    public static String getTrackedDataName(TrackedData<?> data) {
        if (TRACKED_DATA_NAMES.isEmpty()) {
            computeTrackedDataNames();
        }
        String name = TRACKED_DATA_NAMES.get(data);
        return name == null ? "unknown" : name;
    }

    public static String getAllTrackedData(Entity entity) {
        List<DataTracker.Entry<?>> allEntries = entity.getDataTracker().getAllEntries();
        if (allEntries == null || allEntries.isEmpty()) {
            return "<no entries>";
        }

        return allEntries.stream()
                .sorted(Comparator.comparingInt(entry -> entry.getData().getId()))
                .map(entry -> entry.getData().getId() + ": " + getTrackedDataName(entry.getData()) + " = " + entry.get())
                .collect(Collectors.joining("\n"));
    }

    public static void reportRareBug(int bugId) {
        rareBugIdThatOccurred = bugId;
        timeThatRareBugOccurred = System.nanoTime();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread()) {
            mc.send(() -> reportRareBug(bugId));
            return;
        }

        String url = MULTICONNECT_ISSUE_URL.formatted(rareBugIdThatOccurred);
        mc.inGameHud.getChatHud().addMessage(Text.translatable("multiconnect.rareBug", Text.translatable("multiconnect.rareBug.link")
                        .styled(style -> style.withUnderline(true)
                                .withColor(Formatting.BLUE)
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(url)))
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))))
            .formatted(Formatting.YELLOW));
    }

    public static boolean wasRareBugReportedRecently() {
        return rareBugIdThatOccurred != 0 && (System.nanoTime() - timeThatRareBugOccurred) < 10_000_000_000L;
    }

    private static Text getRareBugText(int line) {
        String url = MULTICONNECT_ISSUE_URL.formatted(rareBugIdThatOccurred);
        return Text.translatable("multiconnect.rareBug", Text.translatable("multiconnect.rareBug.link")
                .styled(style -> style.withUnderline(true)
                        .withColor(Formatting.BLUE)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(url)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
    }

    public static Screen createRareBugScreen(Screen parentScreen) {
        URL url;
        try {
            url = new URL(MULTICONNECT_ISSUE_URL.formatted(rareBugIdThatOccurred));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        rareBugIdThatOccurred = 0;
        return new ConfirmScreen(result -> {
            if (result) {
                Util.getOperatingSystem().open(url);
            }
            MinecraftClient.getInstance().setScreen(parentScreen);
        }, parentScreen.getTitle(), Text.translatable("multiconnect.rareBug.screen"));
    }

    @ThreadSafe
    public static boolean isUnexpectedDisconnect(Throwable t) {
        return !(t instanceof PacketEncoderException) && !(t instanceof TimeoutException);
    }

    private static class ErrorHandlerInfo {
        static final ThreadLocal<ErrorHandlerInfo> INSTANCE = ThreadLocal.withInitial(ErrorHandlerInfo::new);

        int wrapperCount = 0;
        boolean handledError = false;
    }

    public static void wrapInErrorHandler(ChannelHandlerContext context, ByteBuf buf, String direction, Runnable runnable) {
        ErrorHandlerInfo handlerInfo = ErrorHandlerInfo.INSTANCE.get();
        handlerInfo.wrapperCount++;
        try {
            runnable.run();
        } catch (Throwable e) {
            if (!handlerInfo.handledError) {
                context.channel().attr(NETTY_HAS_HANDLED_ERROR).set(Boolean.TRUE);
                DebugUtils.logPacketError(buf, "Direction: " + direction);
            }
            // consume all the input
            buf.readerIndex(buf.readerIndex() + buf.readableBytes());
            if (DebugUtils.IGNORE_ERRORS) {
                LOGGER.warn("Ignoring error in packet");
                e.printStackTrace();
            } else {
                handlerInfo.handledError = true;
                throw e;
            }
        } finally {
            if (--handlerInfo.wrapperCount == 0) {
                handlerInfo.handledError = false;
            }
        }
    }

    public static byte[] getBufData(ByteBuf buf) {
        if (buf.hasArray()) {
            return buf.array();
        }
        int prevReaderIndex = buf.readerIndex();
        buf.readerIndex(0);
        byte[] array = new byte[buf.readableBytes()];
        buf.readBytes(array);
        buf.readerIndex(prevReaderIndex);
        return array;
    }

    public static void logPacketError(ByteBuf data, String... extraLines) {
        logPacketError(getBufData(data), extraLines);
    }

    public static void logPacketError(byte[] data, String... extraLines) {
        LOGGER.error("!!!!!!!! Unexpected packet error, please upload this error to " + MULTICONNECT_ISSUES_BASE_URL + " !!!!!!!!");
        LOGGER.error("It may be helpful if you also provide the server IP, but you are not obliged to do this.");
        LOGGER.error("Minecraft version: {}", SharedConstants.getGameVersion().getName());
        FabricLoader.getInstance().getModContainer("multiconnect").ifPresent(modContainer -> {
            String version = modContainer.getMetadata().getVersion().getFriendlyString();
            LOGGER.error("multiconnect version: {}", version);
        });
        LOGGER.error("Server version: {} ({})", ConnectionInfo.protocolVersion, ConnectionMode.byValue(ConnectionInfo.protocolVersion).getName());
        LOGGER.error("Server brand: {}", lastServerBrand);
        for (String extraLine : extraLines) {
            LOGGER.error(extraLine);
        }
        LOGGER.error("Compressed packet data: {}", LogUtils.defer(() -> toCompressedBase64(data)));
        if (!PacketRecorder.ENABLED) {
            LOGGER.error("It's possible to create a full recording of all packets in a session by adding -Dmulticonnect.enablePacketRecorder=true to your JVM args.");
            LOGGER.error("For more complex problems, this may be required to diagnose the issue.");
        }
    }

    public static String toCompressedBase64(byte[] data) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(Base64.getEncoder().wrap(result))) {
            out.write(data);
        } catch (IOException e) {
            return "[error compressing] " + Base64.getEncoder().encodeToString(data);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    public static void handlePacketDump(ClientPlayNetworkHandler networkHandler, String base64, boolean compressed) {
        byte[] bytes = decode(base64, compressed);
        if (bytes == null) {
            return;
        }
        LOGGER.info("Artificially handling packet of length {}", bytes.length);
        Channel channel = ((ClientConnectionAccessor) networkHandler.getConnection()).getChannel();
        assert channel != null;
        ChannelHandlerContext context = channel.pipeline().context("multiconnect_clientbound_translator");
        if (context == null) {
            context = channel.pipeline().context("decoder");
            if (context == null) {
                throw new IllegalStateException("Cannot handle packet dump on singleplayer");
            }
        }
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            buf.readerIndex(0);
            if (channel.eventLoop().inEventLoop()) {
                ((ChannelInboundHandler) context.handler()).channelRead(context, buf);
            } else {
                ChannelHandlerContext context_f = context;
                channel.eventLoop().execute(() -> {
                    try {
                        ((ChannelInboundHandler) context_f.handler()).channelRead(context_f, buf);
                    } catch (Exception e) {
                        throw PacketIntrinsics.sneakyThrow(e);
                    }
                });
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static byte @Nullable [] decode(String base64, boolean compressed) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (!compressed) {
            return bytes;
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            IOUtils.copy(in, result);
        } catch (IOException e) {
            LOGGER.error("Decompression error", e);
            return null;
        }
        return result.toByteArray();
    }

    @SuppressWarnings("unchecked")
    public static <T, R extends Registry<T>> RegistryLike<?> getRegistryLike(Registries registry) {
        return switch (registry) {
            case BLOCK_STATE -> new IndexedIterableRegistryLike<>(Block.STATE_IDS) {
                @Override
                public String getName(BlockState value) {
                    return blockStateToString(value);
                }

                @Override
                public @NotNull Integer serverRawIdToClient(int serverRawId) {
                    return PacketSystem.serverBlockStateIdToClient(serverRawId);
                }
            };
            case TRACKED_DATA_HANDLER -> TrackedDataHandlerRegistryLike.INSTANCE;
            case ENTITY_POSE -> new EnumRegistryLike<>(EntityPose.values());
            default -> {
                if (!registry.isRealRegistry()) {
                    throw new IllegalArgumentException("Cannot get indexed iterable for " + registry);
                }
                RegistryKey<R> registryKey;
                try {
                    Field field = Registry.class.getField(registry.getRegistryKeyFieldName());
                    registryKey = (RegistryKey<R>) field.get(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalArgumentException("Cannot get indexed iterable for " + registry, e);
                }
                yield new RegistryRegistryLike<>(((Registry<R>) Registry.REGISTRIES).get(registryKey));
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <T> void dumpRegistries() throws IOException {
        File dir = new File("../data/" + SharedConstants.getGameVersion().getReleaseTarget());
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        dumpPackets(new File(dir, "spackets.csv"), NetworkSide.CLIENTBOUND, "SPacket", "S2CPacket");
        dumpPackets(new File(dir, "cpackets.csv"), NetworkSide.SERVERBOUND, "CPacket", "C2SPacket");
        for (Registries registries : Registries.values()) {
            RegistryLike<T> registryLike = (RegistryLike<T>) getRegistryLike(registries);
            try (PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, registries.name().toLowerCase(Locale.ROOT) + ".csv")))) {
                pw.println("id name oldName remapTo");
                for (T value : registryLike) {
                    String name = registryLike.getName(value);
                    if (!name.startsWith("multiconnect:")) {
                        pw.println(registryLike.getRawId(value) + " " + name);
                    }
                }
            }
        }

        dumpDynamicRegistries();
    }

    public static String identifierToString(@Nullable Identifier identifier) {
        if (identifier == null) {
            return "null";
        }
        if (identifier.getNamespace().equals("minecraft")) {
            return identifier.getPath();
        }
        return identifier.toString();
    }

    public static String blockStateToString(BlockState state) {
        Identifier unmodifiedName = Registry.BLOCK.getId(state.getBlock());
        String result = identifierToString(unmodifiedName);
        if (state.getBlock().getStateManager().getProperties().isEmpty()) return result;
        String stateStr = state.getBlock().getStateManager().getProperties().stream().map(it -> it.getName() + "=" + getName(it, state.get(it))).collect(Collectors.joining(","));
        return result + "[" + stateStr + "]";
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getName(Property<T> prop, Object value) {
        return prop.name((T) value);
    }

    private static void dumpPackets(File output, NetworkSide side, String prefix, String toReplace) throws IOException {
        var packetHandlers = new ArrayList<>(NetworkState.PLAY.getPacketIdToPacketMap(side).int2ObjectEntrySet());
        packetHandlers.sort(Comparator.comparingInt(Int2ObjectMap.Entry::getIntKey));
        try (PrintWriter pw = new PrintWriter(new FileWriter(output))) {
            pw.println("id clazz");
            for (var entry : packetHandlers) {
                int id = entry.getIntKey();
                String baseClassName = entry.getValue().getName();
                baseClassName = baseClassName.substring(baseClassName.lastIndexOf('.') + 1);
                int underscoreIndex = baseClassName.indexOf('_');
                if (underscoreIndex >= 0) {
                    baseClassName = baseClassName.substring(0, underscoreIndex);
                }
                baseClassName = baseClassName.replace("$", "").replace(toReplace, "");
                baseClassName = prefix + baseClassName;
                String className = "net.earthcomputer.multiconnect.packets." + baseClassName;
                Class<?> clazz;
                try {
                    clazz = Class.forName(className);
                } catch (ClassNotFoundException e) {
                    LOGGER.error("Could not find packet class " + className);
                    continue;
                }
                if (clazz.isInterface()) {
                    className = "net.earthcomputer.multiconnect.packets.latest." + baseClassName + "_Latest";
                    try {
                        Class.forName(className);
                    } catch (ClassNotFoundException e) {
                        LOGGER.error("Could not find packet class " + className);
                        continue;
                    }
                }
                pw.println(id + " " + className);
            }
        }
    }

    private static void dumpDynamicRegistries() throws IOException {
        Path destFile = Path.of("data/registry_manager.nbt");
        Files.createDirectories(destFile.getParent());
        var registryManager = DynamicRegistryManager.createAndLoad().toImmutable();
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(destFile))) {
            NbtCompound nbt = (NbtCompound) DynamicRegistryManager.CODEC.encodeStart(NbtOps.INSTANCE, registryManager)
                    .getOrThrow(false, err -> {});
            NbtIo.writeCompressed(nbt, output);
        }
    }

    public static void onDebugKey() {
    }

    public static String dfuToString(Object dfuType) {
        if (dfuType == null) {
            return "null";
        }
        if (dfuType instanceof Iterable<?> collection) {
            StringBuilder sb = new StringBuilder("[");
            boolean appended = false;
            for (Object o : collection) {
                if (appended) {
                    sb.append(",\n");
                } else {
                    sb.append("\n");
                }
                appended = true;
                sb.append(dfuToString(o));
            }
            return sb.toString().replace("\n", "  \n") + "\n]";
        }

        if (dfuType instanceof TypeRewriteRule.Nop) {
            return "Nop";
        }
        if (dfuType instanceof TypeRewriteRule.Seq) {
            return "Seq" + dfuToString(getField(dfuType, "rules"));
        }
        if (dfuType instanceof TypeRewriteRule.OrElse) {
            return "OrElse[" + dfuToString(getField(dfuType, "first")) + ", " + dfuToString(getField(dfuType, "second")) + "]";
        }
        if (dfuType instanceof TypeRewriteRule.All) {
            return "All[" + dfuToString(getField(dfuType, "rule")) + "]";
        }
        if (dfuType instanceof TypeRewriteRule.One) {
            return "One[" + dfuToString(getField(dfuType, "rule")) + "]";
        }
        if (dfuType instanceof TypeRewriteRule.CheckOnce) {
            return "CheckOnce[" + dfuToString(getField(dfuType, "rule")) + "]";
        }
        if (dfuType instanceof TypeRewriteRule.Everywhere) {
            return "Everywhere[" + dfuToString(getField(dfuType, "rule")) + "]";
        }
        if (dfuType instanceof TypeRewriteRule.IfSame<?>) {
            return "IfSame[" + dfuToString(getField(dfuType, "targetType")) + ", " + dfuToString(getField(dfuType, "value")) + "]";
        }

        return dfuType.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object instance, String name) {
        for (Class<?> clazz = instance.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) {
                Util.throwUnchecked(e);
            }
        }
        throw new IllegalArgumentException("No such field " + name);
    }

    public interface RegistryLike<T> extends Iterable<T> {
        int getRawId(T value);
        T getValue(int id);
        String getName(T value);
        @Nullable
        Integer serverRawIdToClient(int serverRawId);
    }

    private enum TrackedDataHandlerRegistryLike implements RegistryLike<TrackedDataHandler<?>> {
        INSTANCE;

        private static final Int2ObjectMap<String> NAME_MAP = Util.make(new Int2ObjectOpenHashMap<>(), map -> {
            for (Field field : TrackedDataHandlerRegistry.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && TrackedDataHandler.class.isAssignableFrom(field.getType())) {
                    TrackedDataHandler<?> trackedDataHandler;
                    try {
                        trackedDataHandler = (TrackedDataHandler<?>) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError("Failed to access tracked data handler", e);
                    }
                    map.put(TrackedDataHandlerRegistry.getId(trackedDataHandler), field.getName().toLowerCase(Locale.ROOT));
                }
            }
        });

        @Override
        public int getRawId(TrackedDataHandler<?> value) {
            return TrackedDataHandlerRegistry.getId(value);
        }

        @Override
        public TrackedDataHandler<?> getValue(int id) {
            return TrackedDataHandlerRegistry.get(id);
        }

        @Override
        public String getName(TrackedDataHandler<?> value) {
            return NAME_MAP.get(getRawId(value));
        }

        @Override
        public @NotNull Integer serverRawIdToClient(int serverRawId) {
            // TODO: implement tracked data remapping for debug
            return serverRawId;
        }

        @NotNull
        @Override
        public Iterator<TrackedDataHandler<?>> iterator() {
            return new Iterator<>() {
                private int index = 0;
                private TrackedDataHandler<?> next = getValue(index);

                @Override
                public boolean hasNext() {
                    return next != null;
                }

                @Override
                public TrackedDataHandler<?> next() {
                    TrackedDataHandler<?> result = next;
                    next = getValue(++index);
                    return result;
                }
            };
        }
    }

    private record EnumRegistryLike<T extends Enum<T>>(T[] values) implements RegistryLike<T> {
        @Override
        public int getRawId(T value) {
            return value.ordinal();
        }

        @Override
        public T getValue(int index) {
            if (index < 0 || index >= values.length) {
                throw new IndexOutOfBoundsException();
            }
            return values[index];
        }

        @Override
        public String getName(T value) {
            return value.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public @NotNull Integer serverRawIdToClient(int serverRawId) {
            // TODO: implement enum remapping for debug
            return serverRawId;
        }

        @NotNull
        @Override
        public Iterator<T> iterator() {
            return new Iterator<>() {
                private int index = 0;

                @Override
                public boolean hasNext() {
                    return index < values.length;
                }

                @Override
                public T next() {
                    return values[index++];
                }
            };
        }
    }

    private static abstract class IndexedIterableRegistryLike<T> implements RegistryLike<T> {
        private final IndexedIterable<T> registry;

        protected IndexedIterableRegistryLike(IndexedIterable<T> registry) {
            this.registry = registry;
        }

        @Override
        public int getRawId(T value) {
            return registry.getRawId(value);
        }

        @Override
        public T getValue(int index) {
            return registry.get(index);
        }

        @NotNull
        @Override
        public Iterator<T> iterator() {
            return registry.iterator();
        }
    }

    private static final class RegistryRegistryLike<T> extends IndexedIterableRegistryLike<T> {
        private final Registry<T> registry;

        private RegistryRegistryLike(Registry<T> registry) {
            super(registry);
            this.registry = registry;
        }

        @Override
        public String getName(T value) {
            return identifierToString(registry.getId(value));
        }

        @Override
        public @NotNull Integer serverRawIdToClient(int serverRawId) {
            return PacketSystem.serverRawIdToClient(registry, serverRawId);
        }
    }
}