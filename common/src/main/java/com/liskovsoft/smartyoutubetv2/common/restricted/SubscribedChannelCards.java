package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import com.liskovsoft.googleapi.youtubedata3.YouTubeDataServiceInt;
import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import io.reactivex.Observable;
import io.reactivex.functions.Function;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Names and artwork are presentation data; the managed IDs remain authoritative. */
public final class SubscribedChannelCards {
    static final String CACHE_FILE = "subscriptions-channel-cards.json";
    private SubscribedChannelCards() { }

    public static Observable<List<Video>> load(Context context, SubscriptionsOnlyMode.Snapshot scope, List<Video> cards) {
        return load(context, scope, cards, ids -> YouTubeDataServiceInt.getChannelMetadata(ids));
    }

    static Observable<List<Video>> load(Context context, SubscriptionsOnlyMode.Snapshot scope, List<Video> cards,
                                        Function<String[], List<ItemMetadata>> metadata) {
        return Observable.defer(() -> {
            if (!scope.enabled || scope.channels.isEmpty()) return Observable.empty();
            JSONObject cached = readCache(context);
            apply(scope, cards, cached);
            return Observable.just(cards).concatWith(Observable.fromCallable(() -> {
                List<ItemMetadata> result = metadata.apply(scope.channels.keySet().toArray(new String[0]));
                if (result == null) return cards;
                for (ItemMetadata item : result) {
                    if (item == null || !scope.channels.containsKey(item.getChannelId())) continue;
                    JSONObject display = cached.optJSONObject(item.getChannelId());
                    if (display == null) display = new JSONObject();
                    if (item.getTitle() != null && !item.getTitle().isEmpty()) display.put("name", item.getTitle());
                    if (item.getCardImageUrl() != null && !item.getCardImageUrl().isEmpty()) display.put("image", item.getCardImageUrl());
                    cached.put(item.getChannelId(), display);
                }
                writeCache(context, cached);
                apply(scope, cards, cached);
                return cards;
            }).onErrorResumeNext(Observable.empty()));
        });
    }

    private static void apply(SubscriptionsOnlyMode.Snapshot scope, List<Video> cards, JSONObject cached) {
        for (Video card : cards) {
            if (!scope.channels.containsKey(card.channelId)) continue;
            JSONObject display = cached.optJSONObject(card.channelId);
            if (display == null) continue;
            String name = display.optString("name", "");
            String image = display.optString("image", "");
            if (!name.isEmpty()) { card.title = name; card.author = name; }
            if (!image.isEmpty()) { card.cardImageUrl = image; card.bgImageUrl = image; }
        }
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
