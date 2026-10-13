package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import io.reactivex.schedulers.Schedulers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SubscribedChannelCardsTest {
    private Context context;
    private static final String OTHER = "UCCCCCCCCCCCCCCCCCCCCCCC";
    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        clean();
        SubscriptionsOnlyModeTest.configure(context);
    }
    @After public void after() { clean(); }
    private void clean() {
        new File(context.getCacheDir(), SubscribedChannelCards.CACHE_FILE).delete();
        new File(context.getFilesDir(), SubscriptionsOnlyMode.LOCK_FILE).delete();
        new File(context.getFilesDir(), SubscriptionsOnlyMode.CHANNELS_FILE).delete();
    }
    private static ItemMetadata metadata(String id, String name, String image) {
        return (ItemMetadata) Proxy.newProxyInstance(ItemMetadata.class.getClassLoader(), new Class[]{ItemMetadata.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getChannelId": return id;
                        case "getTitle": return name;
                        case "getCardImageUrl": return image;
                        default: return null;
                    }
                });
    }
    @Test public void resolvesNamesAndImagesByApprovedIdWithoutChangingCardIdentity() {
        SubscriptionsOnlyMode.Snapshot scope = SubscriptionsOnlyMode.snapshot(context);
        List<Video> cards = SubscriptionsOnlyMode.channels(scope);
        SubscribedChannelCards.load(context, scope, ids -> {
            assertArrayEquals(new String[]{SubscriptionsOnlyModeTest.A, SubscriptionsOnlyModeTest.B}, ids);
            return Arrays.asList(metadata(OTHER, "Unapproved", "https://example.com/other.png"),
                    metadata(SubscriptionsOnlyModeTest.A, "Current channel name", "https://example.com/channel.png"));
        }).blockingLast().apply(cards);
        assertEquals(2, cards.size());
        Video card = cards.get(0);
        assertEquals(SubscriptionsOnlyModeTest.A, card.channelId);
        assertEquals("Current channel name", card.title);
        assertEquals("Current channel name", card.author);
        assertEquals("https://example.com/channel.png", card.getCardImageUrl());
        assertEquals(MediaItem.TYPE_CHANNEL, card.itemType);
        assertNull(card.videoId);
        assertEquals("Beta", cards.get(1).title);
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context, OTHER));
    }
    @Test public void cachedDisplaySurvivesOfflineButCannotAuthorizeARemovedChannel() throws Exception {
        SubscriptionsOnlyMode.Snapshot scope = SubscriptionsOnlyMode.snapshot(context);
        SubscribedChannelCards.load(context, scope, ids -> Arrays.asList(
                metadata(SubscriptionsOnlyModeTest.A, "Cached name", "https://example.com/a.png"),
                metadata(SubscriptionsOnlyModeTest.B, "Removed", "https://example.com/b.png"))).toList().blockingGet();
        SubscriptionsOnlyModeTest.put(context, SubscriptionsOnlyMode.CHANNELS_FILE,
                "{\"channels\":[{\"id\":\"" + SubscriptionsOnlyModeTest.A + "\"}]}");
        scope = SubscriptionsOnlyMode.snapshot(context);
        List<Video> cards = SubscriptionsOnlyMode.channels(scope);
        SubscribedChannelCards.load(context, scope, ids -> { throw new IOException("offline"); }).doOnNext(update -> update.apply(cards)).test()
                .assertValueCount(1).assertComplete().assertNoErrors();
        assertEquals(1, cards.size());
        assertEquals("Cached name", cards.get(0).title);
        assertEquals("https://example.com/a.png", cards.get(0).getCardImageUrl());
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", SubscriptionsOnlyModeTest.B));
    }
    @Test public void lookupFailureKeepsConfiguredNamesAndCompleteApprovedList() {
        SubscriptionsOnlyMode.Snapshot scope = SubscriptionsOnlyMode.snapshot(context);
        List<Video> cards = SubscriptionsOnlyMode.channels(scope);
        SubscribedChannelCards.load(context, scope, ids -> { throw new IOException("offline"); }).doOnNext(update -> update.apply(cards)).test()
                .assertValueCount(1).assertComplete().assertNoErrors();
        assertEquals(2, cards.size());
        assertEquals("Alpha", cards.get(0).title);
        assertEquals("Beta", cards.get(1).title);
    }
    @Test public void ordinaryModeNeverCallsTheManagedMetadataLookup() {
        new File(context.getFilesDir(), SubscriptionsOnlyMode.LOCK_FILE).delete();
        SubscriptionsOnlyMode.Snapshot scope = SubscriptionsOnlyMode.snapshot(context);
        SubscribedChannelCards.load(context, scope, ids -> {
            throw new AssertionError("Ordinary channels must keep their original path");
        }).test().assertNoValues().assertComplete().assertNoErrors();
    }

    @Test public void workerLookupDoesNotMutateRenderedCardsOrEarlierEmissions() {
        SubscriptionsOnlyMode.Snapshot scope = SubscriptionsOnlyMode.snapshot(context);
        List<Video> cards = SubscriptionsOnlyMode.channels(scope);
        Video original = cards.get(0);
        List<SubscribedChannelCards.Update> updates = SubscribedChannelCards.load(context, scope,
                ids -> Arrays.asList(metadata(SubscriptionsOnlyModeTest.A, "Fresh name", "https://example.com/fresh.png")))
                .subscribeOn(Schedulers.single()).toList().blockingGet();
        assertEquals(2, updates.size());
        assertEquals("Alpha", original.title);
        assertNull(original.getCardImageUrl());
        updates.get(0).apply(cards);
        assertEquals("Alpha", original.title); // The fresh lookup must not mutate the cached emission.
        updates.get(1).apply(cards);
        assertSame(original, cards.get(0));
        assertEquals("Fresh name", original.title);
        assertEquals("https://example.com/fresh.png", original.getCardImageUrl());
        assertEquals(SubscriptionsOnlyModeTest.A, original.channelId);
        assertNull(original.videoId);
    }
}
