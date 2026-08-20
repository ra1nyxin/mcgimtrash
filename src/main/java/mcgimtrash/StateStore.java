package mcgimtrash;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

final class StateStore {
    private static final int MAGIC = 0x4D47494D;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_SIZE = 64 * 1024 * 1024;
    private static final int CHECKSUM_BYTES = Long.BYTES;

    private final Path stateFile;
    private final Path backupFile;

    StateStore(Path dataFolder) {
        this.stateFile = dataFolder.resolve("trash-state.bin");
        this.backupFile = dataFolder.resolve("trash-state.bin.bak");
    }

    LoadResult load() throws IOException {
        IOException primaryFailure = null;
        if (Files.exists(stateFile)) {
            try {
                return new LoadResult(read(stateFile), false);
            } catch (IOException | RuntimeException exception) {
                primaryFailure = asIOException("Cannot read " + stateFile, exception);
            }
        }

        if (Files.exists(backupFile)) {
            try {
                return new LoadResult(read(backupFile), true);
            } catch (IOException | RuntimeException exception) {
                IOException backupFailure = asIOException("Cannot read " + backupFile, exception);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(backupFailure);
                    throw primaryFailure;
                }
                throw backupFailure;
            }
        }

        if (primaryFailure != null) {
            throw primaryFailure;
        }
        return null;
    }

    void save(StoredState state, boolean preserveExistingBackup) throws IOException {
        Files.createDirectories(stateFile.getParent());
        byte[] encoded = encode(state);
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        writeAndSync(temporary, encoded);

        if (!preserveExistingBackup && Files.exists(stateFile)) {
            Path backupTemporary = backupFile.resolveSibling(backupFile.getFileName() + ".tmp");
            Files.copy(stateFile, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
            forceExistingFile(backupTemporary);
            moveReplacing(backupTemporary, backupFile);
        }
        moveReplacing(temporary, stateFile);
    }

    private StoredState read(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 32 || size > MAX_FILE_SIZE) {
            throw new IOException("Invalid state size: " + size);
        }

        byte[] encoded = Files.readAllBytes(file);
        int payloadLength = encoded.length - CHECKSUM_BYTES;
        long expectedChecksum = ByteBuffer.wrap(encoded, payloadLength, CHECKSUM_BYTES).getLong();
        CRC32 crc32 = new CRC32();
        crc32.update(encoded, 0, payloadLength);
        if (crc32.getValue() != expectedChecksum) {
            throw new IOException("State checksum mismatch");
        }

        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded, 0, payloadLength))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != FORMAT_VERSION) {
                throw new IOException("Unsupported state format");
            }

            int completedSweeps = input.readInt();
            long nextSweepAt = input.readLong();
            int warningMask = input.readInt();
            int itemDataLength = input.readInt();
            if (completedSweeps < 0 || completedSweeps > McGimTrash.SWEEPS_PER_CYCLE) {
                throw new IOException("Invalid completed sweep count: " + completedSweeps);
            }
            if (itemDataLength <= 0 || itemDataLength > MAX_FILE_SIZE) {
                throw new IOException("Invalid item data size: " + itemDataLength);
            }

            byte[] itemData = input.readNBytes(itemDataLength);
            if (itemData.length != itemDataLength || input.available() != 0) {
                throw new IOException("Truncated or trailing state data");
            }
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(itemData);
            if (items.length != TrashBin.TOTAL_CONTENT_SLOTS) {
                throw new IOException("Invalid trash slot count: " + items.length);
            }
            return new StoredState(completedSweeps, nextSweepAt, warningMask, items);
        } catch (RuntimeException exception) {
            throw new IOException("Cannot deserialize trash items", exception);
        }
    }

    private byte[] encode(StoredState state) throws IOException {
        byte[] itemData;
        try {
            itemData = ItemStack.serializeItemsAsBytes(state.items());
        } catch (RuntimeException exception) {
            throw new IOException("Cannot serialize trash items", exception);
        }

        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(state.completedSweeps());
            output.writeLong(state.nextSweepAt());
            output.writeInt(state.warningMask());
            output.writeInt(itemData.length);
            output.write(itemData);
        }

        byte[] payload = payloadBytes.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(payload);
        ByteBuffer complete = ByteBuffer.allocate(payload.length + CHECKSUM_BYTES);
        complete.put(payload);
        complete.putLong(crc32.getValue());
        return complete.array();
    }

    private void writeAndSync(Path file, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void forceExistingFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private IOException asIOException(String message, Exception exception) {
        return exception instanceof IOException ioException
                ? ioException
                : new IOException(message, exception);
    }

    record StoredState(int completedSweeps, long nextSweepAt, int warningMask, ItemStack[] items) {
    }

    record LoadResult(StoredState state, boolean recoveredFromBackup) {
    }
}
