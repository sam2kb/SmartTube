package com.liskovsoft.smartyoutubetv2.common.restricted;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import io.reactivex.Observable;

/** Each result retains its channel and its own service continuation. */
public final class SubscribedChannelSearch {
    private SubscribedChannelSearch() { }
    public static final class Result {
        public final String channelId;
        public final String channelName;
        public final MediaGroup group;
        Result(String channelId, String channelName, MediaGroup group) {
            this.channelId = channelId;
            this.channelName = channelName;
            this.group = group;
        }
    }
    public static Observable<MediaGroup> continueSearch(ContentService service, SubscriptionsOnlyMode.Snapshot scope,
                                                       String channelId, MediaGroup group) {
        if (!scope.enabled || !scope.allows(channelId) || group == null) return Observable.empty();
        return service.continueGroupObserve(group);
    }
    public static Observable<Result> search(ContentService service, SubscriptionsOnlyMode.Snapshot scope, String query) {
        if (!scope.enabled || query == null || query.trim().isEmpty()) return Observable.empty();
        return Observable.fromIterable(scope.channels.entrySet())
                .flatMap(channel -> service.getChannelSearchObserve(channel.getKey(), query)
                        .map(group -> new Result(channel.getKey(), channel.getValue(), group))
                        .onErrorResumeNext(Observable.empty()), false, 3);
    }
}
