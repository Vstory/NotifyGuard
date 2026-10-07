# libxposed 官方模块规则（api-master/README.md「For Module Developers」）：
# 允许入口类混淆就必须同时改写 java_init.list，二者才同源
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 再显式保名：CI 产物门禁拿 java_init.list 里的类名去 dex 里找，保名后这条断言不依赖
# `-adaptresourcefilecontents` 是否在 AGP 的资源链路上生效
-keep class io.github.vstory.notifyguard.MainHook { *; }
