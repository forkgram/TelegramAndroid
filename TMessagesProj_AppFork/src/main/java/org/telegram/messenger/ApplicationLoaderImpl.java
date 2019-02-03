package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {

/*
    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return DummyPushListenerServiceProvider.INSTANCE;
    }

    @Override
    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new DummyLocationServiceProvider();
    }

    @Override
    protected IMapsProvider onCreateMapsProvider() {
        if (DummyPushListenerServiceProvider.INSTANCE.hasServices()) {
            return new GoogleMapsProvider();
        }
        return new HuaweiMapsProvider();
    }
*/

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
