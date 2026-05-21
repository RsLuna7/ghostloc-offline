package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

@SuppressWarnings("unused")
public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        String getProcessName();

        boolean isSystemServer();
    }

    interface PackageLoadedParam {
        ApplicationInfo getApplicationInfo();

        ClassLoader getClassLoader();

        ClassLoader getDefaultClassLoader();

        String getPackageName();

        boolean isFirstPackage();
    }

    interface SystemServerLoadedParam {
        ClassLoader getClassLoader();
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }

    default void onSystemServerLoaded(SystemServerLoadedParam param) {
    }
}
