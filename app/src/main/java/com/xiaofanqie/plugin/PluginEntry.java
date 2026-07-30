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

    // 诊断：记录构造函数被调用过
    private volatile boolean constructorHookFired = false;
    // 诊断：记录检测到的组件类型
    private final Set<String> detectedComponentTypes = new HashSet<>();

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

            // Step 1: 连接 QAuxiliary API
            initQauxvAccess();
            showToastDelayed("小番茄插件: QAuxiliary API 已连接", 2000);

            // Step 2: 安装菜单 Hook
            hookMenuSystem();
            showToastDelayed("小番茄插件: 菜单Hook已安装，长按图片试试", 3000);

            log("小番茄解混淆插件启动完成");
        } catch (Throwable e) {
            logError("插件初始化失败", e);
            showToastDelayed("小番茄插件初始化失败: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage(), 2000);
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
        // 复制为 final 变量以在匿名内部类中使用
        final Method fGetMsgMethod = getMsgMethod;

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
        showToastDelayed("小番茄: 找到菜单方法 " + listMethodName, 1000);

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
                        final Object component = callMethod(param, "getThisObject");
                        if (component == null) return;

                        Class<?> componentClass = component.getClass();
                        String className = componentClass.getName();

                        if (!constructorHookFired) {
                            constructorHookFired = true;
                            showToastDelayed("小番茄: 检测到组件 " + componentClass.getSimpleName(), 500);
                        }
                        detectedComponentTypes.add(componentClass.getSimpleName());

                        if (hookedComponentClasses.contains(className)) return;
                        hookedComponentClasses.add(className);

                        // 只处理图片消息组件
                        if (!className.equals(picComponentName)) return;

                        showToastOnMain("小番茄: 检测到图片组件! 类=" + componentClass.getSimpleName());
                        log("Hook 图片菜单组件: " + className);

                        // Step 5: Hook 该组件具体实现的 getMenuList 方法
                        Method listMethod = componentClass.getMethod(finalListMethodName);
                        listMethod.setAccessible(true);
                        log("  -> 具体方法: " + listMethod.getDeclaringClass().getSimpleName() + "." + listMethod.getName());

                        hookMember(listMethod, new SimpleHookCallback() {
                            @Override
                            public void beforeHookedMember(Object param) throws Throwable {
                                // 诊断：确认回调触发
                                showToastOnMain("小番茄: getMenuList 被调用! cls=" + componentClass.getSimpleName());
                            }

                            @Override
                            @SuppressWarnings("unchecked")
                            public void afterHookedMember(Object param) throws Throwable {
                                try {
                                    Object result = callMethod(param, "getResult");
                                    if (result == null) {
                                        showToastOnMain("小番茄: getResult 返回 null!");
                                        return;
                                    }
                                    List menuList = (List) result;

                                    // 注意：必须用当前回调的 getThisObject()，不能用构造函数时捕获的 component！
                                    Object currentComponent = callMethod(param, "getThisObject");
                                    Object msg = fGetMsgMethod.invoke(currentComponent);
                                    if (msg == null) {
                                        showToastOnMain("小番茄: getMsg 返回 null!");
                                        return;
                                    }

                                    showToastOnMain("小番茄: 创建菜单项...");
                                    Object menuItem = createCustomMenuItem(msg);
                                    if (menuItem != null) {
                                        menuList.add(menuItem);
                                        showToastOnMain("小番茄: 已注入! size=" + menuList.size());
                                        log("已注入「小番茄解混淆」菜单项");
                                    } else {
                                        showToastOnMain("小番茄: createCustomMenuItem 返回 null");
                                    }
                                } catch (Throwable t) {
                                    logError("注入菜单项失败", t);
                                    showToastOnMain("小番茄: 异常 - " + t.getClass().getSimpleName()
                                            + ": " + truncate(t.getMessage(), 60));
                                }
                            }
                        });
                    } catch (Throwable t) {
                        logError("构造函数 Hook 回调异常", t);
                        showToastOnMain("小番茄: Hook异常 - " + t.getClass().getSimpleName());
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

        // 关键：必须从 moduleClassLoader 加载 Kotlin 类！
        // CustomMenu.createItemNt 签名中的 Function0 是通过 moduleClassLoader 解析的，
        // 如果从 hostClassLoader 加载会得到不同的 Class 对象，导致 NoSuchMethodException
        Class<?> function0Class = moduleClassLoader.loadClass("kotlin.jvm.functions.Function0");
        Class<?> unitClass = moduleClassLoader.loadClass("kotlin.Unit");
        final Object unitInstance = unitClass.getField("INSTANCE").get(null);

        // 用动态代理创建 Function0<Unit> 实现
        final Object finalMsg = msg;
        Object clickProxy = Proxy.newProxyInstance(
                moduleClassLoader,
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
     * 通过多种方式获取图片本地路径。
     * 不同 QQ 版本的内部 API 类名可能不同，需要多种策略。
     */
    private String getLocalImagePath(Object msg) {
        // 策略1: AIOMsgItemApiImpl（标准 NT QQ API）
        try {
            Class<?> apiImplClass = hostClassLoader.loadClass(
                    "com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl");
            Class<?> aioMsgItemClass = hostClassLoader.loadClass(
                    "com.tencent.mobileqq.aio.msg.AIOMsgItem");
            Object apiImpl = apiImplClass.newInstance();
            Method getLocalPath = apiImplClass.getMethod("getLocalPath", Object.class, Class.class);
            String path = (String) getLocalPath.invoke(apiImpl, msg, aioMsgItemClass);
            if (path != null && !path.isEmpty() && new File(path).exists()) {
                return path;
            }
        } catch (ClassNotFoundException ignored) {
            log("AIOMsgItemApiImpl 不存在，尝试其他方式");
        } catch (Exception e) {
            log("AIOMsgItemApiImpl 调用失败: " + e.getMessage());
        }

        // 策略2: 通过 QAuxiliary 的 Initiator 查找（支持混淆类名）
        try {
            Class<?> initiatorClass = moduleClassLoader.loadClass("io.github.qauxv.util.Initiator");
            Method loadMethod = initiatorClass.getMethod("load", String.class);
            Class<?> apiImplClass = (Class<?>) loadMethod.invoke(null,
                    "com.tencent.qqnt.aio.msg.api.impl.AIOMsgItemApiImpl");
            if (apiImplClass != null) {
                Class<?> aioMsgItemClass = hostClassLoader.loadClass(
                        "com.tencent.mobileqq.aio.msg.AIOMsgItem");
                Object apiImpl = apiImplClass.newInstance();
                Method getLocalPath = apiImplClass.getMethod("getLocalPath", Object.class, Class.class);
                String path = (String) getLocalPath.invoke(apiImpl, msg, aioMsgItemClass);
                if (path != null && !path.isEmpty() && new File(path).exists()) {
                    return path;
                }
            }
        } catch (Exception e) {
            log("Initiator 方式失败: " + e.getMessage());
        }

        // 策略3: 从 PicElement 获取 MD5，搜索 QQ 缓存目录
        try {
            String md5 = getPicMd5(msg);
            if (md5 != null) {
                String hostDataDir = hostApp.getFilesDir().getParentFile().getAbsolutePath();
                showToastOnMain("小番茄: 搜索图片 MD5=" + md5 + " dir=" + hostDataDir);

                // 扩展搜索路径
                String[] searchDirs = {
                        hostDataDir + "/Tencent/MobileQQ/chatpic/chatimg",
                        hostDataDir + "/Tencent/MobileQQ/chatpic/chatraw",
                        hostDataDir + "/Tencent/MobileQQ/chatpic/chathd",
                        hostDataDir + "/Tencent/MobileQQ/diskcache",
                        hostDataDir + "/Tencent/MobileQQ/chatpic",
                        hostDataDir + "/Tencent/MobileQQ",
                };

                // 先精确匹配 MD5 开头的文件
                for (String dir : searchDirs) {
                    File d = new File(dir);
                    if (!d.exists() || !d.isDirectory()) continue;
                    File[] files = d.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        String name = f.getName();
                        if (name.equalsIgnoreCase(md5) || name.startsWith(md5)) {
                            if (f.length() > 0) {
                                log("找到文件: " + f.getAbsolutePath() + " size=" + f.length());
                                return f.getAbsolutePath();
                            }
                        }
                    }
                }

                // 递归搜索 chatpic 目录
                File chatpicDir = new File(hostDataDir + "/Tencent/MobileQQ/chatpic");
                if (chatpicDir.exists()) {
                    String found = searchFileByName(chatpicDir, md5);
                    if (found != null) return found;
                }

                showToastOnMain("小番茄: MD5=" + md5 + " 未在缓存中找到文件");
            }
        } catch (Exception e) {
            log("MD5 搜索方式失败: " + e.getMessage());
        }

        return null;
    }

    /**
     * 从 AIOMsgItem 获取 PicElement 的 MD5。
     */
    private String getPicMd5(Object msg) {
        try {
            Class<?> picElementClass = hostClassLoader.loadClass(
                    "com.tencent.qqnt.kernel.nativeinterface.PicElement");
            for (Method m : msg.getClass().getDeclaredMethods()) {
                if (m.getReturnType() == picElementClass && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    Object element = m.invoke(msg);
                    if (element != null) {
                        Method getMd5 = picElementClass.getMethod("getMd5HexStr");
                        return (String) getMd5.invoke(element);
                    }
                }
            }
        } catch (Exception e) {
            log("获取 PicElement MD5 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 递归搜索目录中文件名包含关键字的文件（限制深度和数量）。
     */
    private String searchFileByName(File dir, String keyword) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        int count = 0;
        for (File f : files) {
            if (count++ > 200) break; // 限制搜索数量
            String name = f.getName();
            if (name.equalsIgnoreCase(keyword) || name.contains(keyword)) {
                if (f.isFile() && f.length() > 0) return f.getAbsolutePath();
            }
            if (f.isDirectory()) {
                String found = searchFileByName(f, keyword);
                if (found != null) return found;
            }
        }
        return null;
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
        // 注意：QAuxiliary 的类在 moduleClassLoader 中，不在 hostClassLoader 中！
        Class<?> callbackInterface = moduleClassLoader.loadClass(
                "io.github.qauxv.loader.hookapi.IHookBridge$IMemberHookCallback");

        // 用动态代理创建 IMemberHookCallback 实例
        Object callbackProxy = Proxy.newProxyInstance(
                moduleClassLoader,
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
        Class<?> iHookBridgeClass = moduleClassLoader.loadClass(
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

    /**
     * 延迟在主线程显示 Toast（用于启动阶段的诊断信息）。
     */
    private void showToastDelayed(final String text, long delayMs) {
        try {
            new Handler(hostApp.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(hostApp, text, Toast.LENGTH_LONG).show();
                }
            }, delayMs);
        } catch (Exception e) {
            log("ToastDelayed: " + text);
        }
    }

    // ==================== 日志 ====================

    private void log(String msg) {
        android.util.Log.d("XiaoFanQie", msg);
    }

    private void logError(String msg, Throwable e) {
        android.util.Log.e("XiaoFanQie", msg, e);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
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
