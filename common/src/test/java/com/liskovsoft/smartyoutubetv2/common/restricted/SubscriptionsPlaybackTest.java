package com.liskovsoft.smartyoutubetv2.common.restricted;

import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.VideoLoaderController;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SubscriptionsPlaybackTest {
    private Context context;
    @Before public void before() throws Exception {context=RuntimeEnvironment.getApplication();SubscriptionsOnlyModeTest.configure(context);}
    @After public void after() {
        new File(context.getFilesDir(),SubscriptionsOnlyMode.LOCK_FILE).delete();
        new File(context.getFilesDir(),SubscriptionsOnlyMode.CHANNELS_FILE).delete();
    }
    @Test public void forgedCardChannelCannotOpenAnyStreamFormat() throws Exception {
        for (String format:new String[]{"containsDashFormats","containsSabrFormats","containsHlsUrl","containsUrlFormats"}) {
            TestController controller=new TestController(context);
            controller.video.channelId=SubscriptionsOnlyModeTest.A; // A card/intent is not playback authorization.
            deliver(controller,info("requested","UCCCCCCCCCCCCCCCCCCCCCCC",format));
            assertFalse(controller.calls.stream().anyMatch(n->n.startsWith("open")));
            assertTrue(controller.calls.contains("setPlayWhenReady"));
        }
    }
    @Test public void staleResponseCannotPlayApprovedChannelForAnotherVideo() throws Exception {
        TestController controller=new TestController(context);
        deliver(controller,info("other",SubscriptionsOnlyModeTest.A,"containsDashFormats"));
        assertFalse(controller.calls.stream().anyMatch(n->n.startsWith("open")));
    }
    private void deliver(TestController controller,MediaItemFormatInfo info) throws Exception {
        Method method=VideoLoaderController.class.getDeclaredMethod("processFormatInfo",MediaItemFormatInfo.class);
        method.setAccessible(true);method.invoke(controller,info);
    }
    private static MediaItemFormatInfo info(String video,String channel,String format) {
        return (MediaItemFormatInfo)Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),new Class[]{MediaItemFormatInfo.class},(p,m,a)->{
            if (m.getName().equals("getVideoId"))return video;
            if (m.getName().equals("getChannelId"))return channel;
            if (m.getReturnType()==boolean.class)return m.getName().equals(format);
            return null;
        });
    }
    private static class TestController extends VideoLoaderController {
        final Context context;final Video video=new Video();final List<String> calls=new ArrayList<>();final PlaybackView player;
        TestController(Context context) {
            this.context=context;video.videoId="requested";
            player=(PlaybackView)Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),new Class[]{PlaybackView.class},(p,m,a)->{calls.add(m.getName());return null;});
        }
        @Override public Context getContext(){return context;}
        @Override public Video getVideo(){return video;}
        @Override public PlaybackView getPlayer(){return player;}
    }
}
