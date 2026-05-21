package io.github.libxposed.api;

@SuppressWarnings("unused")
public abstract class XposedModule implements XposedModuleInterface {
    protected final XposedInterface base;
    protected final ModuleLoadedParam moduleLoadedParam;

    protected XposedModule(XposedInterface base, ModuleLoadedParam param) {
        this.base = base;
        this.moduleLoadedParam = param;
    }
}
