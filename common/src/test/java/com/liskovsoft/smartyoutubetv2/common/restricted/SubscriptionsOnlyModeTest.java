package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Environment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SubscriptionsOnlyModeTest {
    static final String A = "UCAAAAAAAAAAAAAAAAAAAAAA";
    static final String B = "UCBBBBBBBBBBBBBBBBBBBBBB";
    private Context context;
    @Before public void before() { context = RuntimeEnvironment.getApplication(); storageState(Environment.MEDIA_MOUNTED); clean(); }
    @After public void after() { storageState(Environment.MEDIA_MOUNTED); clean(); }
    private void storageState(String state) {
        ShadowEnvironment.setExternalStorageState(state);
        File external = context.getExternalFilesDir(null);
        if (external != null) ShadowEnvironment.setExternalStorageState(external, state);
    }
    private void clean() {
        new android.util.AtomicFile(new File(context.getFilesDir(), SubscriptionsOnlyMode.EXTERNAL_LOCK_STATE)).delete();
        for (File dir : new File[]{context.getFilesDir(), context.getExternalFilesDir(null)}) {
            if (dir != null) {
                new android.util.AtomicFile(new File(dir, SubscriptionsOnlyMode.EXTERNAL_STORAGE_ID)).delete();
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
    @Test public void appRestoreCannotReplaceAdminConfiguration() throws Exception {
        configure(context);
        com.liskovsoft.smartyoutubetv2.common.misc.BackupAndRestoreManager manager =
                new com.liskovsoft.smartyoutubetv2.common.misc.BackupAndRestoreManager(context);
        java.lang.reflect.Method restore = manager.getClass().getDeclaredMethod("restoreData", String.class);
        restore.setAccessible(true);
        restore.invoke(manager, "untrusted-backup");
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertEquals(2, SubscriptionsOnlyMode.snapshot(context).channels.size());
    }
    @Test public void externalLockAlsoEnforcesMode() throws Exception {
        File external=context.getExternalFilesDir(null);
        assertNotNull(external);
        assertTrue(new File(external,SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertFalse(SubscriptionsOnlyMode.allowsChannel(context,A));
    }

    private Context withoutExternalStorage() {
        return new ContextWrapper(context) {
            @Override public File getExternalFilesDir(String type) { return null; }
        };
    }
    @Test public void ordinaryModeStillWorksWithoutExternalStorage() {
        Context unavailable = withoutExternalStorage();
        assertFalse(SubscriptionsOnlyMode.isEnabled(unavailable));
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(unavailable, null, null, null));
    }
    @Test public void externalActivationSurvivesUnavailableStorage() throws Exception {
        File external = context.getExternalFilesDir(null);
        assertNotNull(external);
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        // A fresh context must read durable activation rather than depend on an in-memory snapshot.
        Context unavailable = withoutExternalStorage();
        assertTrue(SubscriptionsOnlyMode.isEnabled(unavailable));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(unavailable, "video", "video", A));
    }

    private File activateExternal() throws Exception {
        File external = context.getExternalFilesDir(null);
        assertNotNull(external);
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        try (FileOutputStream out = new FileOutputStream(new File(external, SubscriptionsOnlyMode.CHANNELS_FILE))) {
            out.write(("{\"channels\":[{\"id\":\"" + A + "\"}]}").getBytes("UTF-8"));
        }
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
        assertTrue(new File(context.getFilesDir(), SubscriptionsOnlyMode.EXTERNAL_LOCK_STATE).isFile());
        return external;
    }
    @Test public void unmountedStorageCannotBeMistakenForAdminUnlock() throws Exception {
        File external = activateExternal();
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).delete());
        storageState(Environment.MEDIA_UNMOUNTED);
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
        storageState(Environment.MEDIA_MOUNTED);
        assertFalse(SubscriptionsOnlyMode.isEnabled(context));
    }
    @Test public void unreadableDirectoryCannotBeMistakenForAdminUnlock() throws Exception {
        File external = activateExternal();
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).delete());
        Context unreadable = new ContextWrapper(context) {
            @Override public File getExternalFilesDir(String type) {
                return new File(external.getAbsolutePath()) {
                    @Override public String[] list() { return null; }
                };
            }
        };
        assertTrue(SubscriptionsOnlyMode.isEnabled(unreadable));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(unreadable, "video", "video", A));
    }
    @Test public void differentStorageCannotClearRememberedActivation() throws Exception {
        activateExternal();
        File replacement = new File(context.getCacheDir(), "other-volume");
        assertTrue(replacement.mkdirs() || replacement.isDirectory());
        Context otherVolume = new ContextWrapper(context) {
            @Override public File getExternalFilesDir(String type) { return replacement; }
        };
        assertTrue(SubscriptionsOnlyMode.isEnabled(otherVolume));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(otherVolume, "video", "video", A));
    }
    @Test public void internalPolicyWinsAndEitherAdminLockKeepsRestriction() throws Exception {
        File external = activateExternal();
        configure(context);
        assertEquals("Alpha", SubscriptionsOnlyMode.snapshot(context).channels.get(A));
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", B));
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).delete());
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertTrue(new File(context.getFilesDir(), SubscriptionsOnlyMode.LOCK_FILE).delete());
        assertFalse(SubscriptionsOnlyMode.isEnabled(context));
    }
    @Test public void externalPolicyReturnsAfterStorageReturns() throws Exception {
        activateExternal();
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", A));
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
    }
    @Test public void activationWriteFailureDeniesEvenAValidExternalPolicy() throws Exception {
        activateExternal();
        put(context, "not-a-directory", "");
        Context brokenInternal = new ContextWrapper(context) {
            @Override public File getFilesDir() { return new File(context.getFilesDir(), "not-a-directory"); }
        };
        assertTrue(SubscriptionsOnlyMode.isEnabled(brokenInternal));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(brokenInternal, "video", "video", A));
        assertTrue(new File(context.getFilesDir(), "not-a-directory").delete());
    }
    @Test public void corruptRememberedActivationDenies() throws Exception {
        put(context, SubscriptionsOnlyMode.EXTERNAL_LOCK_STATE, "broken");
        assertTrue(SubscriptionsOnlyMode.isEnabled(withoutExternalStorage()));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", A));
    }
    @Test public void internalLockCanUseExternalConfiguration() throws Exception {
        File external = activateExternal();
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).delete());
        assertFalse(SubscriptionsOnlyMode.isEnabled(context));
        put(context, SubscriptionsOnlyMode.LOCK_FILE, "");
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", B));
    }
    @Test @Config(sdk = 19) public void legacyAndroidAlsoRetainsActivationWhileUnmounted() throws Exception {
        unmountedStorageCannotBeMistakenForAdminUnlock();
    }
    @Test public void concurrentActivationRetainsOneDurablePolicy() throws Exception {
        File external = context.getExternalFilesDir(null);
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(workers.submit(() -> {
                start.await();
                return SubscriptionsOnlyMode.isEnabled(context);
            }));
            start.countDown();
            for (java.util.concurrent.Future<Boolean> result : results) assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(new File(context.getFilesDir(), SubscriptionsOnlyMode.EXTERNAL_LOCK_STATE).isFile());
            assertTrue(SubscriptionsOnlyMode.isEnabled(withoutExternalStorage()));
            assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", A));
        } finally {
            workers.shutdownNow();
        }
    }
    @Test public void samePathReplacementStorageCannotUnlock() throws Exception {
        File external = activateExternal();
        File detached = new File(external.getParentFile(), "detached-subscriptions-files");
        assertTrue(external.renameTo(detached));
        assertTrue(external.mkdir());
        try {
            assertTrue(new File(detached, SubscriptionsOnlyMode.LOCK_FILE).isFile());
            assertTrue(SubscriptionsOnlyMode.isEnabled(context));
            assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", B));
        } finally {
            assertTrue(external.delete());
            assertTrue(detached.renameTo(external));
        }
    }
    @Test public void enrolledStorageCanBeReadOnlyAndStillAllowAdminUnlock() throws Exception {
        File external = activateExternal();
        storageState(Environment.MEDIA_MOUNTED_READ_ONLY);
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).delete());
        assertFalse(SubscriptionsOnlyMode.isEnabled(context));
    }
    @Test public void incompleteReadOnlyEnrollmentStaysLockedAfterStorageDisappears() throws Exception {
        File external = context.getExternalFilesDir(null);
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        storageState(Environment.MEDIA_MOUNTED_READ_ONLY);
        assertTrue(SubscriptionsOnlyMode.isEnabled(context));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", A));
        assertTrue(SubscriptionsOnlyMode.isEnabled(withoutExternalStorage()));
        assertFalse(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", A));
    }
    @Test public void internalPolicyWinsDuringIncompleteReadOnlyEnrollment() throws Exception {
        File external = context.getExternalFilesDir(null);
        assertTrue(new File(external, SubscriptionsOnlyMode.LOCK_FILE).createNewFile());
        configure(context);
        storageState(Environment.MEDIA_MOUNTED_READ_ONLY);
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(context, "video", "video", B));
        assertTrue(SubscriptionsOnlyMode.allowsResolvedVideo(withoutExternalStorage(), "video", "video", B));
    }
}
