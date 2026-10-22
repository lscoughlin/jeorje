package org.github.lscoughlin.jeorje.swarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery for instances that share one state directory. Each instance writes one small JSON file in
 * {@code <stateDir>/swarm/rendezvous/<peerId>.json}. The file names the peer, its two addresses, its process
 * id, and the time of the last refresh. An instance lists the files of the other instances. It skips a file
 * that is older than {@value #STALE_SECONDS} seconds and a file whose process is not alive. The announcing
 * instance refreshes its file every {@value #REFRESH_SECONDS} seconds.
 *
 * <p>This discovery needs no multicast network. It reads only the rendezvous directory.
 */
public final class LocalRendezvous implements PeerDiscovery {
    private static final Logger log = LoggerFactory.getLogger(LocalRendezvous.class);

    /** A file older than this many seconds is stale. */
    public static final long STALE_SECONDS = 30;
    /** The announcing instance refreshes its file at this interval. */
    public static final long REFRESH_SECONDS = 10;

    /** The content of one rendezvous file. */
    record Record(String peerId, String address, String sshAddress, String name, long pid, long updatedAt) {}

    private final Path directory;
    private final Clock clock;
    private final long pid;
    private ScheduledExecutorService refresher;
    private PeerRecord announced;

    /** @param stateDir the state directory, normally {@code ${HOME}/.jeorje} */
    public LocalRendezvous(Path stateDir, Clock clock) {
        this.directory = stateDir.resolve("swarm").resolve("rendezvous");
        this.clock = clock;
        this.pid = ProcessHandle.current().pid();
    }

    @Override
    public synchronized void announce(PeerRecord self) {
        this.announced = self;
        write(self);
        if (refresher == null) {
            refresher = Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "swarm-rendezvous");
                thread.setDaemon(true);
                return thread;
            });
            refresher.scheduleWithFixedDelay(this::refresh, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Writes the file of the announced peer again, with the current time. */
    synchronized void refresh() {
        if (announced != null) {
            write(announced);
        }
    }

    @Override
    public synchronized void withdraw(String peerId) {
        announced = null;
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
        try {
            Files.deleteIfExists(fileOf(peerId));
        } catch (IOException e) {
            log.atDebug().log(() -> "cannot delete the rendezvous file: " + e.getMessage());
        }
    }

    @Override
    public List<Entry> peers(String exceptPeerId) {
        var out = new ArrayList<Entry>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            var now = clock.millis();
            for (var file : files) {
                var record = read(file);
                if (record == null || record.peerId().equals(exceptPeerId)) {
                    continue;
                }
                if (now - record.updatedAt() > STALE_SECONDS * 1000) {
                    continue;
                }
                if (!alive(record.pid())) {
                    continue;
                }
                out.add(new Entry(record.peerId(), record.address(), record.sshAddress(), record.name()));
            }
        } catch (IOException e) {
            log.atDebug().log(() -> "cannot list the rendezvous directory: " + e.getMessage());
        }
        return out;
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private Path fileOf(String peerId) {
        return directory.resolve(peerId + ".json");
    }

    private Record read(Path file) {
        try {
            var record = Json.MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            return record == null || record.peerId() == null || record.address() == null ? null : record;
        } catch (IOException | RuntimeException e) {
            log.atDebug().log(() -> "cannot read the rendezvous file " + file + ": " + e.getMessage());
            return null;
        }
    }

    private void write(PeerRecord self) {
        var record = new Record(self.peerId(), self.address(), self.sshAddress(),
                self.name() == null ? "" : self.name(), pid, clock.millis());
        try {
            Files.createDirectories(directory);
            var target = fileOf(self.peerId());
            var temp = Files.createTempFile(directory, "write-", ".tmp");
            Files.writeString(temp, Json.MAPPER.writeValueAsString(record), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.atWarn().log(() -> "cannot write the rendezvous file: " + e.getMessage());
        }
    }
}
