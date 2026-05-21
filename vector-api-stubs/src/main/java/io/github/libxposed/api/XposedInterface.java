package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

@SuppressWarnings("unused")
public interface XposedInterface {
    interface Hooker {
    }

    interface MethodUnhooker<T> {
        T getOrigin();

        void unhook();
    }

    interface BeforeHookCallback {
        Object[] getArgs();

        java.lang.reflect.Member getMember();

        Object getThisObject();

        void returnAndSkip(Object result);

        void throwAndSkip(Throwable throwable);
    }

    interface AfterHookCallback {
        Object[] getArgs();

        java.lang.reflect.Member getMember();

        Object getResult();

        Object getThisObject();

        Throwable getThrowable();

        boolean isSkipped();

        void setResult(Object result);

        void setThrowable(Throwable throwable);
    }

    boolean deoptimize(Constructor<?> constructor);

    boolean deoptimize(Method method);

    ApplicationInfo getApplicationInfo();

    String getFrameworkName();

    int getFrameworkPrivilege();

    String getFrameworkVersion();

    long getFrameworkVersionCode();

    SharedPreferences getRemotePreferences(String group);

    MethodUnhooker<Constructor<?>> hook(Constructor<?> constructor, int priority, Class<? extends Hooker> hooker);

    MethodUnhooker<Constructor<?>> hook(Constructor<?> constructor, Class<? extends Hooker> hooker);

    MethodUnhooker<Method> hook(Method method, int priority, Class<? extends Hooker> hooker);

    MethodUnhooker<Method> hook(Method method, Class<? extends Hooker> hooker);

    MethodUnhooker<?> hookClassInitializer(Class<?> clazz, int priority, Class<? extends Hooker> hooker);

    MethodUnhooker<?> hookClassInitializer(Class<?> clazz, Class<? extends Hooker> hooker);

    Object invokeOrigin(Method method, Object thisObject, Object[] args) throws Throwable;

    void invokeOrigin(Constructor<?> constructor, Object thisObject, Object[] args) throws Throwable;

    Object invokeSpecial(Method method, Object thisObject, Object[] args) throws Throwable;

    void invokeSpecial(Constructor<?> constructor, Object thisObject, Object[] args) throws Throwable;

    void log(int priority, String tag, String msg, Throwable throwable);

    void log(String msg);

    void log(String msg, Throwable throwable);

    Object newInstanceOrigin(Constructor<?> constructor, Object[] args) throws Throwable;

    Object newInstanceSpecial(Constructor<?> constructor, Class<?> subClass, Object[] args) throws Throwable;

    String[] listRemoteFiles();

    ParcelFileDescriptor openRemoteFile(String name);

    Object parseDex(ByteBuffer buffer, boolean includeAnnotations);
}
