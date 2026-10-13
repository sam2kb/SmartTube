package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import io.reactivex.Observable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SubscribedChannelSearchTest {
    private Context context;
    private final List<String> queries=new ArrayList<>();
    private final List<MediaGroup> pages=new ArrayList<>();
    private boolean failFirst;
    private int globalCalls;
    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();
        SubscriptionsOnlyModeTest.configure(context);
    }
    @After public void after() {
        new File(context.getFilesDir(),SubscriptionsOnlyMode.LOCK_FILE).delete();
        new File(context.getFilesDir(),SubscriptionsOnlyMode.CHANNELS_FILE).delete();
    }
    private static MediaGroup group() {
        return (MediaGroup) Proxy.newProxyInstance(MediaGroup.class.getClassLoader(),new Class[]{MediaGroup.class},
                (proxy,method,args)->null);
    }
    private ContentService service() {
        return (ContentService) Proxy.newProxyInstance(ContentService.class.getClassLoader(),new Class[]{ContentService.class},
                (proxy,method,args)->{
                    if (method.getName().equals("getChannelSearchObserve")) {
                        queries.add((String)args[0]);
                        if (failFirst && args[0].equals(SubscriptionsOnlyModeTest.A)) return Observable.error(new RuntimeException("offline"));
                        return Observable.just(group());
                    }
                    if (method.getName().equals("continueGroupObserve")) { pages.add((MediaGroup)args[0]);return Observable.just(group()); }
                    globalCalls++;
                    throw new AssertionError("Global search/autocomplete must not be called");
                });
    }
    @Test public void searchesOnlyBothApprovedChannels() {
        List<SubscribedChannelSearch.Result> rows=SubscribedChannelSearch.search(service(),SubscriptionsOnlyMode.snapshot(context),"science").toList().blockingGet();
        assertEquals(2,rows.size());
        assertEquals(SubscriptionsOnlyModeTest.A,rows.get(0).channelId);
        assertEquals(SubscriptionsOnlyModeTest.B,rows.get(1).channelId);
        assertEquals(2,queries.size());
        assertEquals(0,globalCalls);
    }
    @Test public void oneFailureNeverFallsBackToGlobalSearch() {
        failFirst=true;
        List<SubscribedChannelSearch.Result> rows=SubscribedChannelSearch.search(service(),SubscriptionsOnlyMode.snapshot(context),"science").toList().blockingGet();
        assertEquals(1,rows.size());
        assertEquals(SubscriptionsOnlyModeTest.B,rows.get(0).channelId);
        assertEquals(0,globalCalls);
    }
    @Test public void paginationKeepsItsOwnGroupAndRechecksMembership() throws Exception {
        ContentService service=service();
        SubscriptionsOnlyMode.Snapshot scope=SubscriptionsOnlyMode.snapshot(context);
        List<SubscribedChannelSearch.Result> rows=SubscribedChannelSearch.search(service,scope,"science").toList().blockingGet();
        SubscribedChannelSearch.continueSearch(service,scope,rows.get(1).channelId,rows.get(1).group).test().assertValueCount(1);
        assertSame(rows.get(1).group,pages.get(0));
        SubscriptionsOnlyModeTest.put(context,SubscriptionsOnlyMode.CHANNELS_FILE,"{\"channels\":[]}");
        SubscribedChannelSearch.continueSearch(service,SubscriptionsOnlyMode.snapshot(context),rows.get(1).channelId,rows.get(1).group).test().assertNoValues();
        assertEquals(1,pages.size());
    }
    @Test public void emptyListNeverQueriesAnything() throws Exception {
        SubscriptionsOnlyModeTest.put(context,SubscriptionsOnlyMode.CHANNELS_FILE,"{\"channels\":[]}");
        SubscribedChannelSearch.search(service(),SubscriptionsOnlyMode.snapshot(context),"science").test().assertNoValues();
        assertTrue(queries.isEmpty());assertEquals(0,globalCalls);
    }
}
