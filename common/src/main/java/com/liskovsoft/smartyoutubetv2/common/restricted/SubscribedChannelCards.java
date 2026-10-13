package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import androidx.annotation.MainThread;
import com.liskovsoft.googleapi.youtubedata3.YouTubeDataServiceInt;
import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import io.reactivex.Observable;
import io.reactivex.functions.Function;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Names and artwork are presentation data; the managed IDs remain authoritative. */
public final class SubscribedChannelCards {
    static final String CACHE_FILE = "subscriptions-channel-cards.json";
    private SubscribedChannelCards() { }

    /** Each emission owns immutable display values, never the view's mutable cards. */
    public static final class Update {
        private final Map<String, Display> values;
        private Update(SubscriptionsOnlyMode.Snapshot scope, JSONObject cached) {
            Map<String, Display> result = new LinkedHashMap<>();
            for (String id : scope.channels.keySet()) {
                JSONObject display = cached.optJSONObject(id);
                if (display != null) result.put(id, new Display(display.optString("name", ""), display.optString("image", "")));
            }
            values = Collections.unmodifiableMap(result);
        }

        @MainThread
        public void apply(List<Video> cards) {
            for (Video card : cards) {
                Display display = values.get(card.channelId);
                if (display == null) continue;
                if (!display.name.isEmpty()) { card.title = display.name; card.author = display.name; }
                if (!display.image.isEmpty()) { card.cardImageUrl = display.image; card.bgImageUrl = display.image; }
            }
        }
    }

    private static final class Display {
        final String name;
        final String image;
        Display(String name, String image) { this.name = name; this.image = image; }
    }

    public static Observable<Update> load(Context context, SubscriptionsOnlyMode.Snapshot scope) {
        return load(context, scope, ids -> YouTubeDataServiceInt.getChannelMetadata(ids));
    }

    static Observable<Update> load(Context context, SubscriptionsOnlyMode.Snapshot scope,
                                  Function<String[], List<ItemMetadata>> metadata) {
        return Observable.defer(() -> {
            if (!scope.enabled || scope.channels.isEmpty()) return Observable.empty();
            JSONObject cached = readCache(context);
            return Observable.just(new Update(scope, cached)).concatWith(Observable.fromCallable(() -> {
                List<ItemMetadata> result = metadata.apply(scope.channels.keySet().toArray(new String[0]));
                if (result == null) return new Update(scope, cached);
                for (ItemMetadata item : result) {
                    if (item == null || !scope.channels.containsKey(item.getChannelId())) continue;
                    JSONObject display = cached.optJSONObject(item.getChannelId());
                    if (display == null) display = new JSONObject();
                    if (item.getTitle() != null && !item.getTitle().isEmpty()) display.put("name", item.getTitle());
                    if (item.getCardImageUrl() != null && !item.getCardImageUrl().isEmpty()) display.put("image", item.getCardImageUrl());
                    cached.put(item.getChannelId(), display);
                }
                writeCache(context, cached);
                return new Update(scope, cached);
            }).onErrorResumeNext(Observable.empty()));
        });
    }

    private static JSONObject readCache(Context context) {
        try {
            File file = new File(context.getCacheDir(), CACHE_FILE);
            if (file.isFile() && file.length() <= 1024 * 1024) return new JSONObject(FileHelpers.getFileContents(file));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private static void writeCache(Context context, JSONObject cached) {
        File temp = null;
        try {
            temp = File.createTempFile("channel-cards-", ".json", context.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(cached.toString().getBytes("UTF-8")); }
            temp.renameTo(new File(context.getCacheDir(), CACHE_FILE));
        } catch (Exception ignored) {
            // A cache write failure must not hide the channel list or affect authorization.
        } finally {
            if (temp != null) temp.delete();
        }
    }
}
