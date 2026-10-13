package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

    public static Snapshot snapshot(Context context) {
        boolean locked = false;
        try {
            File internal = context.getFilesDir();
            File external = context.getExternalFilesDir(null);
            locked = new File(internal, LOCK_FILE).exists() || external != null && new File(external, LOCK_FILE).exists();
            if (!locked) return new Snapshot(false, Collections.emptyMap());
            File config = new File(internal, CHANNELS_FILE);
            if (!config.isFile() && external != null) config = new File(external, CHANNELS_FILE);
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
