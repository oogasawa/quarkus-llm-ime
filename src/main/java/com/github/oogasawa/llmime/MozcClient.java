package com.github.oogasawa.llmime;

import jakarta.enterprise.context.ApplicationScoped;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client for mozc_server IPC using abstract Unix domain socket + protobuf.
 * Uses jnr-unixsocket for abstract namespace support and half-close framing.
 */
@ApplicationScoped
public class MozcClient {

    private static final Logger LOG = Logger.getLogger(MozcClient.class.getName());

    private volatile String socketKey;

    /**
     * Get conversion candidates from mozc for the given hiragana input.
     *
     * @param hiragana hiragana string to convert
     * @return list of candidate strings, empty if mozc is unavailable
     */
    public List<String> getCandidates(String hiragana) {
        if (hiragana == null || hiragana.isBlank()) {
            return List.of();
        }

        try {
            String key = getSocketKey();
            if (key == null) {
                return List.of();
            }

            // Create session
            Output createOut = sendRequest(key, Input.newBuilder()
                    .setType(Input.CommandType.CREATE_SESSION)
                    .build());
            if (createOut == null || createOut.getErrorCode() != Output.ErrorCode.SESSION_SUCCESS) {
                LOG.warning("Failed to create mozc session");
                return List.of();
            }
            long sessionId = createOut.getId();

            try {
                return convertInSession(key, sessionId, hiragana);
            } finally {
                sendRequest(key, Input.newBuilder()
                        .setType(Input.CommandType.DELETE_SESSION)
                        .setId(sessionId)
                        .build());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Mozc conversion failed", e);
            // Socket key may be stale if mozc_server restarted
            socketKey = null;
            return List.of();
        }
    }

    /**
     * Segment result: reading + candidates for one bunsetsu.
     */
    public record SegmentResult(String reading, List<String> candidates) {}

    /**
     * Get segmented conversion from mozc.
     * Returns a list of segments, each with its reading and candidates.
     */
    public List<SegmentResult> getSegments(String hiragana) {
        if (hiragana == null || hiragana.isBlank()) {
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }

        try {
            String key = getSocketKey();
            if (key == null) {
                return List.of(new SegmentResult(hiragana, List.of(hiragana)));
            }

            Output createOut = sendRequest(key, Input.newBuilder()
                    .setType(Input.CommandType.CREATE_SESSION)
                    .build());
            if (createOut == null || createOut.getErrorCode() != Output.ErrorCode.SESSION_SUCCESS) {
                return List.of(new SegmentResult(hiragana, List.of(hiragana)));
            }
            long sessionId = createOut.getId();

            try {
                return segmentInSession(key, sessionId, hiragana);
            } finally {
                sendRequest(key, Input.newBuilder()
                        .setType(Input.CommandType.DELETE_SESSION)
                        .setId(sessionId)
                        .build());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Mozc segment failed", e);
            socketKey = null;
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }
    }

    /**
     * Get segmented conversion within a session.
     * Mozc's preedit contains segments with annotation.key (reading) and value (converted).
     */
    private List<SegmentResult> segmentInSession(String key, long sessionId, String hiragana)
            throws IOException {
        // Send hiragana
        sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.TEXT_INPUT)
                        .setKeyString(hiragana))
                .build());

        // Press Space to trigger conversion
        Output convOut = sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.SPACE))
                .build());

        if (convOut == null || !convOut.hasPreedit()) {
            return List.of(new SegmentResult(hiragana, List.of(hiragana)));
        }

        var segments = new ArrayList<SegmentResult>();
        for (var seg : convOut.getPreedit().getSegmentList()) {
            String value = seg.getValue();
            String reading = seg.hasKey() ? seg.getKey() : value;
            var candidates = new ArrayList<String>();
            candidates.add(value);
            if (!value.equals(reading)) {
                candidates.add(reading);
            }
            segments.add(new SegmentResult(reading, candidates));
        }

        return segments.isEmpty()
                ? List.of(new SegmentResult(hiragana, List.of(hiragana)))
                : segments;
    }

    /**
     * Perform conversion within an established session.
     */
    private List<String> convertInSession(String key, long sessionId, String hiragana)
            throws IOException {
        // Send hiragana as key_string with TEXT_INPUT special key
        Output keyOut = sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.TEXT_INPUT)
                        .setKeyString(hiragana))
                .build());
        if (keyOut == null) {
            return List.of();
        }

        // Press Space to trigger conversion
        Output convOut = sendRequest(key, Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder()
                        .setSpecialKey(KeyEvent.SpecialKey.SPACE))
                .build());
        if (convOut == null) {
            return List.of();
        }

        var candidates = new LinkedHashSet<String>();

        // Preedit gives the top conversion
        if (convOut.hasPreedit()) {
            var sb = new StringBuilder();
            for (var seg : convOut.getPreedit().getSegmentList()) {
                sb.append(seg.getValue());
            }
            String top = sb.toString().strip();
            if (!top.isEmpty()) {
                candidates.add(top);
            }
        }

        // all_candidate_words contains the full candidate list
        if (convOut.hasAllCandidateWords()) {
            for (var cw : convOut.getAllCandidateWords().getCandidatesList()) {
                String val = cw.getValue().strip();
                if (!val.isEmpty()) {
                    candidates.add(val);
                }
            }
        }

        // candidate_window may have additional candidates
        if (convOut.hasCandidateWindow()) {
            for (var c : convOut.getCandidateWindow().getCandidateList()) {
                String val = c.getValue().strip();
                if (!val.isEmpty()) {
                    candidates.add(val);
                }
            }
        }

        // If only preedit with no candidate list, press Space again to expand
        if (candidates.size() <= 1) {
            Output moreOut = sendRequest(key, Input.newBuilder()
                    .setType(Input.CommandType.SEND_KEY)
                    .setId(sessionId)
                    .setKey(KeyEvent.newBuilder()
                            .setSpecialKey(KeyEvent.SpecialKey.SPACE))
                    .build());
            if (moreOut != null && moreOut.hasAllCandidateWords()) {
                for (var cw : moreOut.getAllCandidateWords().getCandidatesList()) {
                    String val = cw.getValue().strip();
                    if (!val.isEmpty()) {
                        candidates.add(val);
                    }
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Send a protobuf Input to mozc_server and receive Output.
     * Uses abstract Unix socket with half-close framing.
     */
    private Output sendRequest(String key, Input input) throws IOException {
        // Abstract socket: prepend \0 to the path
        String abstractPath = "\0tmp/.mozc." + key + ".session";
        UnixSocketAddress addr = new UnixSocketAddress(abstractPath);

        try (UnixSocketChannel channel = UnixSocketChannel.open(addr)) {
            // Send serialized Input
            byte[] data = input.toByteArray();
            ByteBuffer sendBuf = ByteBuffer.wrap(data);
            while (sendBuf.hasRemaining()) {
                channel.write(sendBuf);
            }

            // Half-close: signal end of request
            channel.shutdownOutput();

            // Read response
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteBuffer recvBuf = ByteBuffer.allocate(65536);
            while (true) {
                recvBuf.clear();
                int n = channel.read(recvBuf);
                if (n <= 0) break;
                recvBuf.flip();
                byte[] chunk = new byte[recvBuf.remaining()];
                recvBuf.get(chunk);
                baos.write(chunk);
            }

            byte[] responseBytes = baos.toByteArray();
            if (responseBytes.length == 0) {
                return null;
            }
            return Output.parseFrom(responseBytes);
        }
    }

    /**
     * Discover the mozc socket key from the session.ipc file.
     */
    private String getSocketKey() {
        if (socketKey != null) {
            return socketKey;
        }

        Path[] paths = {
            Path.of(System.getProperty("user.home"), ".config", "mozc", ".session.ipc"),
            Path.of(System.getProperty("user.home"), ".mozc", ".session.ipc"),
        };

        for (Path p : paths) {
            if (Files.exists(p)) {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    var info = mozc.ipc.Ipc.IPCPathInfo.parseFrom(bytes);
                    if (info.hasKey() && !info.getKey().isEmpty()) {
                        socketKey = info.getKey();
                        LOG.info("Mozc socket key: " + socketKey);
                        return socketKey;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to read mozc session.ipc from " + p, e);
                }
            }
        }

        LOG.warning("Mozc .session.ipc not found; mozc candidates unavailable");
        return null;
    }
}
