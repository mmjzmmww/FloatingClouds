package top.mmjz.floatingclouds;

import android.app.Application;

import org.jetbrains.annotations.NotNull;

public final class App extends Application {
    public static final Companion Companion = new Companion();
    public static App instance;

    public void onCreate() {
        super.onCreate();
        top.mmjz.floatingclouds.util.AppContext.context = this;
        top.mmjz.floatingclouds.util.FileConfigStore.INSTANCE.init(this);
        Companion.setInstance(this);
    }

    public static final class Companion {
        private Companion() {
        }

        public final App getInstance() {
            App app = instance;
            if (app != null) {
                return app;
            }
            return null;
        }

        public final void setInstance(@NotNull App app) {
            instance = app;
        }

    }
}
