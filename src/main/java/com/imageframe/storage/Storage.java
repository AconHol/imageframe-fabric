package com.imageframe.storage;

import com.imageframe.ImageFrameFabric;
import com.imageframe.ImageFrameMod;
import com.imageframe.image.ImageMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * File based persistence: {@code config/imageframe/images/<id>/meta.nbt} plus raw colour
 * buffers. This mirrors the default file storage of the original plugin.
 */
public final class Storage {

    private final ImageFrameMod mod;
    private final Path root;
    private final Path imagesDir;
    private final Path stateFile;

    public Storage(ImageFrameMod mod) {
        this.mod = mod;
        this.root = FabricLoader.getInstance().getConfigDir().resolve("imageframe");
        this.imagesDir = root.resolve("images");
        this.stateFile = root.resolve("state.nbt");
    }

    public Path root() {
        return root;
    }

    public void load() {
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.error("Unable to create ImageFrame data directory", e);
            return;
        }
        mod.manager().resetRegistry();
        int nextImageIndex = 1;
        int fakeIdCounter = 1_600_000_000;
        if (Files.isRegularFile(stateFile)) {
            try {
                CompoundTag state = NbtIo.readCompressed(stateFile, NbtAccounter.unlimitedHeap());
                nextImageIndex = state.getIntOr("nextImageIndex", 1);
                fakeIdCounter = state.getIntOr("fakeIdCounter", 1_600_000_000);
            } catch (IOException e) {
                ImageFrameFabric.LOGGER.warn("Unable to read ImageFrame state", e);
            }
        }
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> stream = Files.list(imagesDir)) {
            stream.filter(Files::isDirectory).forEach(directories::add);
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.error("Unable to list image maps", e);
        }
        directories.sort(Comparator.comparingInt(Storage::directoryIndex));
        int loaded = 0;
        int maxFakeId = fakeIdCounter;
        for (Path directory : directories) {
            ImageMap image = loadImage(directory);
            if (image == null) {
                continue;
            }
            mod.manager().register(image);
            loaded++;
            for (int id : image.allFakeMapIds()) {
                maxFakeId = Math.max(maxFakeId, id + 1);
            }
        }
        mod.manager().setCounters(Math.max(nextImageIndex, maxLoadedIndex() + 1), maxFakeId);
        ImageFrameFabric.LOGGER.info("Loaded {} image map(s)", loaded);
    }

    private int maxLoadedIndex() {
        int max = 0;
        for (ImageMap image : mod.manager().all()) {
            max = Math.max(max, image.index());
        }
        return max;
    }

    private static int directoryIndex(Path path) {
        try {
            return Integer.parseInt(path.getFileName().toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private ImageMap loadImage(Path directory) {
        Path metaFile = directory.resolve("meta.nbt");
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(metaFile, NbtAccounter.unlimitedHeap());
            ImageMap image = ImageMap.fromMetadata(mod.manager(), tag);
            if (image.index() < 0) {
                return null;
            }
            Path colorsDir = directory.resolve("colors");
            int tiles = image.tileCount();
            if (image.isAnimated()) {
                int distinct = image.distinctCount();
                byte[][][] colors = new byte[tiles][distinct][];
                for (int tile = 0; tile < tiles; tile++) {
                    for (int frame = 0; frame < distinct; frame++) {
                        Path file = colorsDir.resolve("t" + tile + "_d" + frame + ".bin");
                        if (Files.isRegularFile(file)) {
                            byte[] data = Files.readAllBytes(file);
                            if (data.length == 128 * 128) {
                                colors[tile][frame] = data;
                            }
                        }
                    }
                }
                image.setAnimationColors(colors);
            } else {
                byte[][] colors = new byte[tiles][];
                for (int tile = 0; tile < tiles; tile++) {
                    Path file = colorsDir.resolve("t" + tile + ".bin");
                    if (Files.isRegularFile(file)) {
                        byte[] data = Files.readAllBytes(file);
                        if (data.length == 128 * 128) {
                            colors[tile] = data;
                        }
                    }
                }
                image.setStaticColors(colors);
            }
            return image;
        } catch (Exception e) {
            ImageFrameFabric.LOGGER.error("Unable to load image map from {}", directory, e);
            return null;
        }
    }

    public void saveState() {
        try {
            Files.createDirectories(root);
            CompoundTag state = new CompoundTag();
            state.putInt("nextImageIndex", mod.manager().nextImageIndex());
            state.putInt("fakeIdCounter", mod.manager().fakeIdCounter());
            NbtIo.writeCompressed(state, stateFile);
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.warn("Unable to save ImageFrame state", e);
        }
    }

    public void saveImage(ImageMap image) {
        Path directory = imagesDir.resolve(String.valueOf(image.index()));
        Path colorsDir = directory.resolve("colors");
        try {
            Files.createDirectories(colorsDir);
            int tiles = image.tileCount();
            if (image.isAnimated()) {
                byte[][][] colors = image.animationColors();
                int distinct = image.distinctCount();
                for (int tile = 0; tile < tiles; tile++) {
                    for (int frame = 0; frame < distinct; frame++) {
                        Path file = colorsDir.resolve("t" + tile + "_d" + frame + ".bin");
                        byte[] data = colors != null && tile < colors.length && frame < colors[tile].length
                                ? colors[tile][frame] : null;
                        if (data == null) {
                            Files.deleteIfExists(file);
                        } else {
                            Files.write(file, data);
                        }
                    }
                }
            } else {
                byte[][] colors = image.staticColors();
                for (int tile = 0; tile < tiles; tile++) {
                    Path file = colorsDir.resolve("t" + tile + ".bin");
                    byte[] data = colors != null && tile < colors.length ? colors[tile] : null;
                    if (data == null) {
                        Files.deleteIfExists(file);
                    } else {
                        Files.write(file, data);
                    }
                }
            }
            NbtIo.writeCompressed(image.toMetadata(), directory.resolve("meta.nbt"));
            saveState();
        } catch (IOException e) {
            ImageFrameFabric.LOGGER.warn("Unable to save image map #{}", image.index(), e);
        }
    }

    public void deleteImage(ImageMap image) {
        Path directory = imagesDir.resolve(String.valueOf(image.index()));
        deleteRecursively(directory);
        saveState();
    }

    /**
     * Imports image map folders from another ImageFrame storage directory.
     *
     * @return the number of imported image maps, or {@code -1} if the directory is not an
     *         ImageFrame-Fabric storage directory
     */
    public int importFrom(Path otherRoot) throws IOException {
        Path otherImages = otherRoot.resolve("images");
        if (!Files.isDirectory(otherImages)) {
            return -1;
        }
        Files.createDirectories(imagesDir);
        int imported = 0;
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> stream = Files.list(otherImages)) {
            stream.filter(Files::isDirectory).forEach(directories::add);
        }
        directories.sort(Comparator.comparingInt(Storage::directoryIndex));
        for (Path directory : directories) {
            Path target = imagesDir.resolve(directory.getFileName().toString());
            if (Files.exists(target)) {
                continue;
            }
            copyRecursively(directory, target);
            imported++;
        }
        load();
        return imported;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(each -> {
                try {
                    Files.deleteIfExists(each);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    public void saveAll() {
        for (ImageMap image : mod.manager().all()) {
            if (image.valid()) {
                saveImage(image);
            }
        }
        saveState();
    }
}
