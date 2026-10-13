package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.AtomicFile;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Administrator-controlled local subscriptions; no in-app unlock or list mutation. */
public final class SubscriptionsOnlyMode {
    public static final String LOCK_FILE = ".subscriptions_only.lock";
    public static final String CHANNELS_FILE = "subscriptions-only.json";
    static final String EXTERNAL_LOCK_STATE = ".subscriptions_only.external";
    static final String EXTERNAL_STORAGE_ID = ".subscriptions_only.storage";
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    private SubscriptionsOnlyMode() { }

    public static final class Snapshot {
        public final boolean enabled;
        public final Map<String, String> channels;
        private Snapshot(boolean enabled, Map<String, String> channels) {
            this.enabled = enabled;
            this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
        }
        public boolean allows(String channelId) {
            return !enabled || channelId != null && CHANNEL_ID.matcher(channelId).matches() && channels.containsKey(channelId);
        }
        public boolean samePolicy(Snapshot other) {
            return other != null && enabled == other.enabled && channels.equals(other.channels);
        }
    }

    public static synchronized Snapshot snapshot(Context context) {
        try {
            File internal = context.getFilesDir();
            File external = context.getExternalFilesDir(null);
            if (internal == null) return denied();
            boolean internalLocked = new File(internal, LOCK_FILE).exists();
            boolean externalLocked = external != null && new File(external, LOCK_FILE).exists();
            AtomicFile state = new AtomicFile(new File(internal, EXTERNAL_LOCK_STATE));
            ExternalLock remembered = readExternalLock(state);
            if (!internalLocked && !externalLocked && remembered == null) return new Snapshot(false, Collections.emptyMap());
            boolean readable = external != null && readableExternal(external);
            String storageId = readable && (externalLocked || remembered != null) ? readStorageId(external) : null;
            if (externalLocked) {
                if (storageId == null) storageId = remembered != null && remembered.directory.equals(external.getAbsolutePath()) ?
                        remembered.storageId : java.util.UUID.randomUUID().toString();
                if (remembered == null || !remembered.matches(external, storageId)) {
                    writeState(state, external.getAbsolutePath(), storageId);
                    remembered = readExternalLock(state);
                    if (remembered == null || !remembered.matches(external, storageId)) return denied();
                }
                if (readable && readStorageId(external) == null && Environment.MEDIA_MOUNTED.equals(externalState(external))) {
                    writeState(new AtomicFile(new File(external, EXTERNAL_STORAGE_ID)), storageId);
                    if (!storageId.equals(readStorageId(external))) return denied();
                }
            }
            storageId = readable && remembered != null ? readStorageId(external) : null;
            boolean originalStorage = readable && remembered != null && remembered.matches(external, storageId);
            if (!internalLocked && !externalLocked && originalStorage) {
                // Both absent sentinels on the enrolled storage mean an administrator unlock.
                state.delete();
                if (stateExists(state)) return denied();
                return new Snapshot(false, Collections.emptyMap());
            }
            File config = new File(internal, CHANNELS_FILE);
            if (!config.isFile() && readable && (remembered == null || originalStorage)) {
                config = new File(external, CHANNELS_FILE);
            }
            if (!config.isFile() || config.length() > 1024 * 1024) return denied();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(config)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 1024 * 1024) return denied();
                }
            }
            JSONArray rows = new JSONObject(bytes.toString("UTF-8")).getJSONArray("channels");
            Map<String, String> channels = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.getString("id");
                if (!CHANNEL_ID.matcher(id).matches()) return denied();
                channels.put(id, row.optString("name", id));
            }
            return new Snapshot(true, channels);
        } catch (Exception error) {
            // An unreadable lock/config must never unlock a managed installation.
            return denied();
        }
    }

    private static final class ExternalLock {
        final String directory;
        final String storageId;
        ExternalLock(String directory, String storageId) { this.directory = directory; this.storageId = storageId; }
        boolean matches(File external, String id) {
            return external != null && directory.equals(external.getAbsolutePath()) && storageId.equals(id);
        }
    }

    private static boolean stateExists(AtomicFile state) {
        return state.getBaseFile().exists() || new File(state.getBaseFile() + ".bak").exists();
    }

    private static ExternalLock readExternalLock(AtomicFile state) throws Exception {
        if (!stateExists(state)) return null;
        try (DataInputStream input = new DataInputStream(state.openRead())) {
            String directory = input.readUTF();
            String id = input.readUTF();
            if (!new File(directory).isAbsolute() || id.isEmpty()) throw new java.io.IOException("Invalid external lock state");
            return new ExternalLock(directory, id);
        }
    }

    private static String readStorageId(File external) throws Exception {
        AtomicFile state = new AtomicFile(new File(external, EXTERNAL_STORAGE_ID));
        if (!stateExists(state)) return null;
        try (DataInputStream input = new DataInputStream(state.openRead())) {
            String id = input.readUTF();
            if (id.isEmpty()) throw new java.io.IOException("Invalid storage identity");
            return id;
        }
    }

    private static void writeState(AtomicFile state, String... values) throws Exception {
        FileOutputStream output = state.startWrite();
        try {
            DataOutputStream data = new DataOutputStream(output);
            for (String value : values) data.writeUTF(value);
            data.flush();
            output.getFD().sync();
            state.finishWrite(output);
        } catch (Exception error) {
            state.failWrite(output);
            throw error;
        }
    }

    private static String externalState(File directory) {
        return Build.VERSION.SDK_INT >= 21 ? Environment.getExternalStorageState(directory) : Environment.getExternalStorageState();
    }

    private static boolean readableExternal(File directory) {
        String state = externalState(directory);
        return (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) && directory.list() != null;
    }

    private static Snapshot denied() { return new Snapshot(true, Collections.emptyMap()); }
    public static boolean isEnabled(Context context) { return snapshot(context).enabled; }
    public static boolean allowsChannel(Context context, String channelId) { return snapshot(context).allows(channelId); }
    public static boolean isCurrent(Context context, Snapshot previous) { return snapshot(context).samePolicy(previous); }
    public static boolean allowsResolvedVideo(Context context, String requestedId, String resolvedId, String channelId) {
        Snapshot current = snapshot(context);
        return !current.enabled || requestedId != null && requestedId.equals(resolvedId) && current.allows(channelId);
    }
    public static List<Video> channels(Context context) { return channels(snapshot(context)); }
    public static List<Video> channels(Snapshot scope) {
        List<Video> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : scope.channels.entrySet()) {
            Video video = new Video();
            video.channelId = entry.getKey();
            video.title = entry.getValue();
            video.author = entry.getValue();
            video.itemType = MediaItem.TYPE_CHANNEL;
            video.isSubscribed = true;
            result.add(video);
        }
        return result;
    }
}
