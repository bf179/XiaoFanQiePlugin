package com.xiaofanqie.plugin;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QAuxiliary 外部插件入口类。
 * <p>
 * 该插件在 QQ 图片消息长按菜单中添加「小番茄解混淆」按钮，
 * 点击后基于 Gilbert 空间填充曲线对图片进行解混淆（与 xiaofanqiehunxiao.com 算法一致）并保存到相册目录。
 * <p>
 * 外部插件必须遵循以下约定：
 * 1. APK 根目录下包含 META-INF/qauxv/module.prop 文件，指定 entry 为此类
 * 2. 入口类必须实现 Runnable 接口
 * 3. 必须提供构造函数 {@code (String modulePath, String hostDataDir, Map<String, Method> xblService)}
 * <p>
 * 参考实现：QAuxiliary 内置的 PicMd5Hook、PicCopyToClipboard
 *
 * @see io.github.qauxv.chainloader.detail.ExternalModuleChainLoader
 * @see io.github.qauxv.util.CustomMenu
 */
public class PluginEntry implements Runnable {

    private final String modulePath;
    private final String hostDataDir;

    // QAuxiliary 的 ClassLoader，用于反射访问其内部 API
    private ClassLoader moduleClassLoader;
    // QQ 宿主的 ClassLoader，用于访问 QQ 的类
    private ClassLoader hostClassLoader;
    // IHookBridge 实例，用于 Hook 方法
    private Object hookBridge;
    // QQ Application
    private Application hostApp;

    // 记录已 Hook 的组件类名，避免重复 Hook
    private final Set<String> hookedComponentClasses = new HashSet<>();

    /**
     * QAuxiliary 外部插件系统要求的构造函数。
     *
     * @param modulePath  插件 APK 的路径
     * @param hostDataDir QQ 的 dataDir 路径
     * @param xblService  保留参数（当前未使用）
     */
    public PluginEntry(String modulePath, String hostDataDir, Map<String, Method> xblService) {
        this.modulePath = modulePath;
        this.hostDataDir = hostDataDir;
    }

    @Override
    public void run() {
        try {
            log("小番茄解混淆插件启动中...");
            initQauxvAccess();
            hookMenuSystem();
            log("小番茄解混淆插件启动完成");
        } catch (Throwable e) {
            logError("插件初始化失败", e);
        }
    }

    // ==================== 初始化 ====================

    /**
     * 通过 ChainLoaderAgent 获取 QAuxiliary 和 QQ 的运行时环境。
     * ChainLoaderAgent 位于 io.github.qauxv.chainloader.api 包下，
     * 该包在 ChainLoaderParentClassLoader 中被代理到 QAuxiliary 的 ClassLoader，
     * 因此外部插件可以直接通过 Class.forName 加载。
     */
    private void initQauxvAccess() throws Exception {
        Class<?> chainLoaderAgent = Class.forName("io.github.qauxv.chainloader.api.ChainLoaderAgent");

        moduleClassLoader = (ClassLoader) chainLoaderAgent.getMethod("getModuleClassLoader").invoke(null);
        hostClassLoader = (ClassLoader) chainLoaderAgent.getMethod("getHostClassLoader").invoke(null);
        hostApp = (Application) chainLoaderAgent.getMethod("getHostApplication").invoke(null);
        hookBridge = chainLoaderAgent.getMethod("getHookBridge").invoke(null);

        log("QAuxiliary 环境初始化完成");
    }

    // ==================== 菜单 Hook 核心逻辑 ====================

    /**
     * Hook QQ NT 的消息长按菜单构建流程。
     * <p>
     * 原理：参考 QAuxiliary 的 MenuBuilderHook，
     * Hook BaseContentComponent 的构造函数 → 检测 AIOPicContentComponent（图片消息组件），
     * 然后 Hook 该组件的 getMenuList 方法 → 在菜单列表中注入自定义菜单项。
     * <p>
     * QQ NT 菜单构建流程：
     * 1. 用户长按消息 → 创建 BaseContentComponent 子类实例
     * 2. 调用组件的 getMenuList() 方法获取菜单列表
     * 3. 显示菜单
     */
    private void hookMenuSystem() throws Exception {
        // Step 1: 从宿主 ClassLoader 加载关键类
        Class<?> baseContentComp = hostClassLoader.loadClass(
                "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent");
        Class<?> aioMsgItemClass = hostClassLoader.loadClass(
                "com.tencent.mobileqq.aio.msg.AIOMsgItem");

        // Step 2: 找到 getMsg() 方法（返回 AIOMsgItem，无参数）
        Method getMsgMethod = null;
        for (Method m : baseContentComp.getDeclaredMethods()) {
            if (m.getReturnType() == aioMsgItemClass && m.getParameterTypes().length == 0) {
                getMsgMethod = m;
                getMsgMethod.setAccessible(true);
                break;
            }
        }
        if (getMsgMethod == null) {
            logError("无法找到 BaseContentComponent.getMsg() 方法", null);
            return;
        }

        // Step 3: 找到抽象的 getMenuList 方法名（返回 List，无参数，abstract）
        String listMethodName = null;
        for (Method m : baseContentComp.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())
                    && m.getReturnType() == List.class
                    && m.getParameterTypes().length == 0) {
                listMethodName = m.getName();
                break;
            }
        }
        if (listMethodName == null) {
            logError("无法找到 BaseContentComponent 的抽象 getMenuList 方法", null);
            return;
        }
        log("菜单列表方法名: " + listMethodName);

        final String finalListMethodName = listMethodName;
        final String picComponentName = "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent";

        // Step 4: Hook BaseContentComponent 的所有构造函数
        // 当子类（如 AIOPicContentComponent）的构造函数调用 super() 时，这些 Hook 会被触发
        Constructor<?>[] constructors = baseContentComp.getDeclaredConstructors();
        for (Constructor<?> ctor : constructors) {
            ctor.setAccessible(true);
            hookMember(ctor, new SimpleHookCallback() {
                @Override
                public void afterHookedMember(Object param) throws Throwable {
                    try {
                        Object component = callMethod(param, "getThisObject");
                        if (component == null) return;

                        Class<?> componentClass = component.getClass();
                        String className = componentClass.getName();

                        if (hookedComponentClasses.contains(className)) return;
                        hookedComponentClasses.add(className);

                        // 只处理图片消息组件
                        if (!className.equals(picComponentName)) return;

                        log("Hook 图片菜单组件: " + className);

                        // Step 5: Hook 该组件具体实现的 getMenuList 方法
                        Method listMethod = componentClass.getMethod(finalListMethodName);
                        listMethod.setAccessible(true);

                        hookMember(listMethod, new SimpleHookCallback() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public void afterHookedMember(Object param) throws Throwable {
                                try {
                                    List menuList = (List) callMethod(param, "getResult");
                                    if (menuList == null) return;

                                    Object msg = getMsgMethod.invoke(component);
                                    if (msg == null) return;

                                    Object menuItem = createCustomMenuItem(msg);
                                    if (menuItem != null) {
                                        menuList.add(menuItem);
                                        log("已注入「小番茄解混淆」菜单项");
                                    }
                                } catch (Throwable t) {
                                    logError("注入菜单项失败", t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        logError("构造函数 Hook 回调异常", t);
                    }
                }
            });
        }
        log("菜单系统 Hook 已安装");
    }

    /**
     * 通过反射调用 QAuxiliary 的 CustomMenu.createItemNt() 方法创建自定义菜单项。
     * <p>
     * createItemNt 使用 ByteBuddy 动态生成 AbstractQQCustomMenuItem 的子类，
     * 并通过 InMemoryDexClassLoader 加载。
     * <p>
     * 由于外部插件不能直接依赖 QAuxiliary 的类，这里通过反射调用，
     * 并用动态代理模拟 Kotlin lambda 参数。
     */
    private Object createCustomMenuItem(Object msg) throws Exception {
        // 加载 QAuxiliary 的 CustomMenu
        Class<?> customMenuClass = moduleClassLoader.loadClass("io.github.qauxv.util.CustomMenu");

        // 加载 Kotlin Function0 接口（Kotlin lambda 编译后实现此接口）
        Class<?> function0Class = hostClassLoader.loadClass("kotlin.jvm.functions.Function0");
        Class<?> unitClass = hostClassLoader.loadClass("kotlin.Unit");
        final Object unitInstance = unitClass.getField("INSTANCE").get(null);

        // 用动态代理创建 Function0<Unit> 实现，等价于 Kotlin 的 { performDeobfuscation(msg); Unit }
        final Object finalMsg = msg;
        Object clickProxy = Proxy.newProxyInstance(
                hostClassLoader,
                new Class<?>[]{function0Class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        performDeobfuscation(finalMsg);
                        return unitInstance;
                    }
                }
        );

        // 菜单项 ID（自定义范围，避免与 QQ 内置 ID 冲突）
        int menuId = 0x7F0A0101;

        // 调用 CustomMenu.createItemNt(msg, "小番茄解混淆", menuId, clickCallback)
        Method createItemNt = customClassGetMethod(customMenuClass, "createItemNt",
                Object.class, String.class, int.class, function0Class);

        return createItemNt.invoke(null, msg, "小番茄解混淆", menuId, clickProxy);
    }

    // ==================== 图片解混淆功能 ====================

    /**
     * 对图片进行小番茄解混淆（Gilbert 曲线逆置乱）并保存到相册目录。
     * <p>
     * 算法与 xiaofanqiehunxiao.com 网站的「解混淆」按钮完全一致：
     * 1. 生成 Gilbert 空间填充曲线坐标序列
     * 2. 使用黄金比例偏移量进行像素配对交换
     * 3. 解混淆：像素从配对位置移回原位
     * <p>
     * 注意：此解混淆操作与网站的算法一致。如果图片经过多次混淆，
     * 需要对应次数的解混淆才能还原。单次混淆对应单次解混淆。
     *
     * @param msg QQ NT 的 AIOMsgItem 对象
     */
    private void performDeobfuscation(Object msg) {
        try {
            // Step 1: 获取图片本地文件路径
            String filePath = getLocalImagePath(msg);
            if (filePath == null || filePath.isEmpty()) {
                showToastOnMain("无法获取图片路径，请先点击查看原图");
                return;
            }

            File srcFile = new File(filePath);
            if (!srcFile.exists()) {
                showToastOnMain("图片文件不存在，请先查看原图");
                return;
            }

            log("源文件路径: " + filePath + ", 大小: " + srcFile.length());

            // Step 2: 解码为 Bitmap
            Bitmap sourceBitmap = BitmapFactory.decodeFile(srcFile.getAbsolutePath());
            if (sourceBitmap == null) {
                showToastOnMain("图片解码失败，文件可能已损坏");
                return;
            }

            int width = sourceBitmap.getWidth();
            int height = sourceBitmap.getHeight();
            log("图片尺寸: " + width + "x" + height);

            // Step 3: Gilbert 曲线解混淆（与网站「解混淆」按钮一致）
            // 如果图片像素过多，性能可能较差，建议控制在 800 万像素以内
            long startTime = System.currentTimeMillis();
            Bitmap unscrambled = GilbertCurve.unscramble(sourceBitmap);
            long elapsed = System.currentTimeMillis() - startTime;
            log("Gilbert 解混淆完成，耗时: " + elapsed + "ms");

            // 释放源 Bitmap
            if (unscrambled != sourceBitmap) {
                sourceBitmap.recycle();
            }

            // Step 4: 保存到 Pictures/小番茄解混淆 目录
            String fileName = "QA_" + System.currentTimeMillis() + ".jpg";
            File outDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "小番茄解混淆");
            if (!outDir.exists() && !outDir.mkdirs()) {
                showToastOnMain("无法创建保存目录: " + outDir.getAbsolutePath());
                unscrambled.recycle();
                return;
            }
            File outFile = new File(outDir, fileName);

            FileOutputStream fos = new FileOutputStream(outFile);
            try {
                unscrambled.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            } finally {
                fos.close();
            }
            unscrambled.recycle();

            log("解混淆图片已保存: " + outFile.getAbsolutePath());
            showToastOnMain("小番茄解混淆完成!\n已保存至: " + outFile.getAbsolutePath());

        } catch (Throwable e) {
            logError("图片解混淆失败", e);
            showToastOnMain("解混淆失败: " + e.getMessage());
        }
    }

    /**
     * 通过 QQ 的 AIOMsgItemApiImpl.getLocalPath() 获取图片本地路径。
     */
    private String getLocalImagePath(Object msg) throws Exception {
        Class<?> apiImplClass = hostClassLoader.loadClass(
                "com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl");
        Class<?> aioMsgItemClass = hostClassLoader.loadClass(
                "com.tencent.mobileqq.aio.msg.AIOMsgItem");

        Object apiImpl = apiImplClass.newInstance();
        Method getLocalPath = apiImplClass.getMethod("getLocalPath", Object.class, Class.class);
        return (String) getLocalPath.invoke(apiImpl, msg, aioMsgItemClass);
    }

    // ==================== Hook 基础设施 ====================

    /**
     * 使用 IHookBridge 对方法/构造函数进行 Hook。
     *
     * @param member   需要 Hook 的 Method 或 Constructor
     * @param callback 回调接口
     */
    private void hookMember(Member member, SimpleHookCallback callback) throws Exception {
        // 加载 IHookBridge$IMemberHookCallback 接口
        Class<?> callbackInterface = hostClassLoader.loadClass(
                "io.github.qauxv.loader.hookapi.IHookBridge$IMemberHookCallback");

        // 用动态代理创建 IMemberHookCallback 实例
        Object callbackProxy = Proxy.newProxyInstance(
                hostClassLoader,
                new Class<?>[]{callbackInterface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("beforeHookedMember".equals(method.getName()) && args.length > 0) {
                            callback.beforeHookedMember(args[0]);
                        } else if ("afterHookedMember".equals(method.getName()) && args.length > 0) {
                            callback.afterHookedMember(args[0]);
                        }
                        return null;
                    }
                }
        );

        // 获取 PRIORITY_DEFAULT 常量
        Class<?> iHookBridgeClass = hostClassLoader.loadClass(
                "io.github.qauxv.loader.hookapi.IHookBridge");
        int priority = iHookBridgeClass.getField("PRIORITY_DEFAULT").getInt(null);

        // 调用 hookBridge.hookMethod(ctor, callback, priority)
        Method hookMethodMethod = hookBridge.getClass().getMethod("hookMethod",
                Member.class, callbackInterface, int.class);
        hookMethodMethod.invoke(hookBridge, member, callbackProxy, priority);
    }

    // ==================== 反射工具方法 ====================

    /**
     * 反射调用对象的方法（按名称和参数个数匹配）。
     */
    private Object callMethod(Object obj, String methodName, Object... args) throws Exception {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterTypes().length == args.length) {
                return m.invoke(obj, args);
            }
        }
        throw new NoSuchMethodException(methodName + " not found");
    }

    /**
     * 使用准确的参数类型查找 Method（处理 ClassLoader 隔离问题）。
     */
    private Method customClassGetMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramTypes.length) continue;
            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!m.getParameterTypes()[i].isAssignableFrom(paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match) return m;
        }
        throw new NoSuchMethodException(name);
    }

    // ==================== UI 工具方法 ====================

    /**
     * 在主线程显示 Toast。
     */
    private void showToastOnMain(final String text) {
        try {
            Handler mainHandler = new Handler(hostApp.getMainLooper());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(hostApp, text, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            log("Toast: " + text);
        }
    }

    // ==================== 日志 ====================

    private void log(String msg) {
        android.util.Log.d("XiaoFanQie", msg);
    }

    private void logError(String msg, Throwable e) {
        android.util.Log.e("XiaoFanQie", msg, e);
    }

    // ==================== 内部接口 ====================

    /**
     * 简化的 Hook 回调接口。
     */
    private interface SimpleHookCallback {
        default void beforeHookedMember(Object param) throws Throwable {
        }

        void afterHookedMember(Object param) throws Throwable;
    }
}
