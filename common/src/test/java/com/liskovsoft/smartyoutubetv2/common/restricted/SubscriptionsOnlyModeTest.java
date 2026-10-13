package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SubscriptionsOnlyModeTest {
    static final String A = "UCAAAAAAAAAAAAAAAAAAAAAA";
    static final String B = "UCBBBBBBBBBBBBBBBBBBBBBB";
    private Context context;
    @Before public void before() { context = RuntimeEnvironment.getApplication(); clean(); }
    @After public void after() { clean(); }
    private void clean() {
        for (File dir : new File[]{context.getFilesDir(), context.getExternalFilesDir(null)}) {
            if (dir != null) {
                new File(dir, SubscriptionsOnlyMode.LOCK_FILE).delete();
                new File(dir, SubscriptionsOnlyMode.CHANNELS_FILE).delete();
            }
        }
    }
    static void put(Context context, String name, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) {
            out.write(text.getBytes("UTF-8"));
        }
    }
    static void configure(Context context) throws Exception {
        put(context, SubscriptionsOnlyMode.LOCK_FILE, "");
        put(context, SubscriptionsOnlyMode.CHANNELS_FILE,
                "{\"channels\":[{\"id\":\""+A+"\",\"name\":\"Alpha\"},{\"id\":\""+B+"\",\"name\":\"Beta\"}]}");
    }
    @Test public void absentLockPreservesOrdinaryBehavior() {
        assertFalse(SubscriptionsOnlyMode.isEnabled(context));
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, null, null, null));
    }
    @Test public void missingOrMalformedConfigDenies() throws Exception {
        put(context, SubscriptionsOnlyMode.LOCK_FILE, "");
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context, A));
        put(context, SubscriptionsOnlyMode.CHANNELS_FILE, "broken");
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context, A));
    }
    @Test public void resolvedOwnerAndRequestedVideoMustMatch() throws Exception {
        configure(context);
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video1", "video1", A));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video1", "video1", "UCCCCCCCCCCCCCCCCCCCCCCC"));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video1", "other", A));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video1", "video1", null));
    }
    @Test public void namesCannotAuthorizeChannels() throws Exception {
        configure(context);
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context, "Alpha"));
        put(context, SubscriptionsOnlyMode.CHANNELS_FILE, "{\"channels\":[{\"id\":\"Alpha\"}]}");
        assertTrue(SubscriptionsOnlyMode.snapshot(context).channels.isEmpty());
    }
    @Test public void adminRevisionInvalidatesPendingWork() throws Exception {
        configure(context);
        SubscriptionsOnlyMode.Snapshot before = SubscriptionsOnlyMode.snapshot(context);
        put(context, SubscriptionsOnlyMode.CHANNELS_FILE, "{\"channels\":[]}");
        assertFalse(SubscriptionsOnlyMode.isCurrent(context, before));
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context, A));
    }
    @Test public void externalLockAlsoEnforcesMode() throws Exception {
        File external=context.getExternalFilesDir(null);
        assertNotNull(external);
        assertTrue(new File(external,SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context,A));
    }
}
