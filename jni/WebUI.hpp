
#ifdef _WIN32
#include <windows.h>
#ifndef PATH_MAX
#define PATH_MAX MAX_PATH
#endif
#else
#include <unistd.h>
#include <limits.h>
#include <sys/wait.h>
#endif

// --- 注册表查询 ---
#ifdef _WIN32
int try_read_reg(HKEY root, const char* subkey, char* out_path, DWORD size) {
    HKEY hKey;
    int success = 0;
    if (RegOpenKeyExA(root, subkey, 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
        if (RegQueryValueExA(hKey, "", NULL, NULL, (LPBYTE)out_path, &size) == ERROR_SUCCESS) {
            success = 1;
        }
        RegCloseKey(hKey);
    }
    return success;
}
#endif

// --- 获取浏览器路径 ---
const char* find_browser_path() {
    static char path[PATH_MAX] = {0};
    if (path[0] != 0) return path;

#ifdef _WIN32
    const char* app_paths[] = {
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe",
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe"
    };
    HKEY roots[] = {HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};

    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            if (try_read_reg(roots[i], app_paths[j], path, sizeof(path))) return path;
        }
    }
#else
    const char* browsers[] = {"google-chrome", "chromium", "chromium-browser", "microsoft-edge"};
    for (int i = 0; i < 4; i++) {
        // 先检查常见绝对路径
        char full_path[64];
        my_snprintf(full_path, sizeof(full_path), "/usr/bin/%s", browsers[i]);
        if (access(full_path, X_OK) == 0) {
            strcpy(path, full_path);
            return path;
        }
    }

    // 使用which命令查找
    FILE* fp = popen("which google-chrome chromium chromium-browser microsoft-edge", "r");
    if(fp) {
        if(fgets(path, sizeof(path), fp)) {
            path[strcspn(path, "\n")] = 0; // 去除换行符
            pclose(fp);
            return path;
        }
        pclose(fp);
    }
#endif
    return NULL;
}

static const char* const CHROMIUM_ARGUMENTS[] = {
        // 不持久化数据
        "--incognito",
        //"--disk-cache-size=1",              // 强制磁盘缓存最小化
        //"--media-cache-size=1",             // 强制媒体缓存最小化
        "--disable-infobars",
        // 禁用无用功能
        "--no-first-run",                   // 跳过初次启动
        "--no-default-browser-check",       // 跳过默认浏览器检查
        "--disable-default-apps",           // 跳过安装默认应用
        "--disable-sync",                   // 禁用账户同步
        "--disable-safe-browsing",          // 禁用安全检查 (Safe Browsing)
        "--disable-autofill",               // 禁用自动填充
        "--disable-cloud-import",           // 禁用云端导入
        "--disable-component-cloud-policy", // 禁用云端策略检查
        "--disable-extensions",             // 禁用浏览器扩展
        "--disable-plugins",                // 禁用插件
        "--disable-component-update",       // 禁止组件更新 chrome://components/
        "--disable-logging",                // 禁用日志记录
        "--disable-translate",              // 禁用翻译
        "--disable-breakpad",               // 禁用崩溃报告
        "--disable-reading-list",           // 禁用阅读清单
        "--disable-client-side-phishing-detection",  // 禁用钓鱼网站检测(减少用户文件夹大小)
        "--disable-background-networking",  // 禁用Chrome自身的后台网络通信
        "--disable-domain-reliability",     // 禁止检测Google是否能访问
        "--metrics-recording-only",         // 禁用UMA报告
        "--no-pings",                       // 不发送超链接审核 ping
        // 优化
        "--no-user-gesture-required",       // 允许网页自动发出声音
        "--disable-background-timer-throttling", // 禁用"降低后台JavaScript计时器频率"
        "--disable-features="
        // 禁用自动填充服务器通信，Chrome优化指南，投屏，默认媒体控件...
        "AutofillServerCommunication,OptimizationHints,MediaRouter,GlobalMediaControls,Translate,BlinkGenPropertyTrees,"
        // 音频服务运行在主进程中
        "AudioServiceOutOfProcess,"
        // 不更新一些东西
        "CertificateTransparencyComponentUpdater,SafeBrowsingRealtimeUrlCheck,UsageStats,"
        "--enable-features=NetworkServiceInProcess",
        "--in-process-gpu",                 // GPU在主进程
        // 其它
        "--disable-databases",              // 禁用WebSQL
        "--disable-save-password-bubble",   // 禁用保存密码对话框
        "--disable-single-click-autofill",  // 关闭自动填充
        "--disable-hang-monitor",           // 禁用"网站无响应"对话框
        "--disable-popup-blocking",         // 禁用弹窗拦截
        "--disable-dev-shm-usage",          // 避免使用/dev/shm
        "--disable-prompt-on-repost",       // 禁用表单重提交提示
        "--enable-low-end-device-mode",     // 低端设备优化
};

#pragma pack (1)
typedef struct {
    const char* browser_path;
    const char* extra_arguments;
    uint16_t width;
    uint16_t height;
    uint16_t x;
    uint16_t y;
    uint32_t pid;
} WebUILaunchOptions;
#pragma pack ()

int launch_chromium(const char* url, const char* user_data_dir, WebUILaunchOptions* options) {
    const char* exe_path = options->browser_path;
    if (exe_path == NULL) exe_path = find_browser_path();
    if(!exe_path) return 1;

#ifdef _WIN32
    char cmd[2048] = {0};
    int offset = my_snprintf(cmd, sizeof(cmd), "\"%s\" --user-data-dir=\"%s\" --app=\"%s\" --homepage=\"%s\"", exe_path, user_data_dir, url, url);

    for (int i = 0; i < sizeof(CHROMIUM_ARGUMENTS)/sizeof(CHROMIUM_ARGUMENTS[0]); i++) {
        offset += my_snprintf(cmd + offset, sizeof(cmd) - offset, " %s", CHROMIUM_ARGUMENTS[i]);
    }

    if (options->extra_arguments)
        offset += my_snprintf(cmd + offset, sizeof(cmd) - offset, " %s", options->extra_arguments);
    if (options->width)
        offset += my_snprintf(cmd + offset, sizeof(cmd) - offset, " --window-size=%d,%d", options->width, options->height);
    if (options->x)
        offset += my_snprintf(cmd + offset, sizeof(cmd) - offset, " --window-position=%d,%d", options->x, options->y);

    STARTUPINFOA si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if(!CreateProcess(
        exe_path,
        cmd,
        NULL,
        NULL,
        FALSE,
        0,
        NULL,
        NULL,
        &si,
        &pi)) {
        return GetLastError();
    }

    options->pid = pi.dwProcessId;

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
#else
    const char* args[64];
    int i = 0;
    args[i++] = exe_path;

    char arg0[PATH_MAX];
    char arg1[PATH_MAX];
    char arg2[PATH_MAX];
    my_snprintf(arg0, sizeof(arg0), "--user-data-dir=%s", user_data_dir);
    my_snprintf(arg1, sizeof(arg1), "--app=%s", url);
    my_snprintf(arg2, sizeof(arg2), "--homepage=%s", url);

    args[i++] = arg0;
    args[i++] = arg1;
    args[i++] = arg2;

    for (int j = 0; j < sizeof(CHROMIUM_ARGUMENTS)/sizeof(CHROMIUM_ARGUMENTS[0]); j++) {
        args[i++] = CHROMIUM_ARGUMENTS[j];
    }

    args[i] = NULL;

    pid_t pid = fork();
    if(pid == 0) {
        execvp(exe_path, (const char*)args);
        exit(1); // 如果执行失败
    } else if(pid < 0) {
        return 2;
    }
#endif

    return 0;
}

JNIEXPORT jint JNICALL Java_roj_webui_WebUI_launch0(JNIEnv* env, jclass, jstring url, jstring user_data_dir, jlong pOptions) {
    const char* arg1 = env->GetStringUTFChars(url, 0);
    const char* arg2 = env->GetStringUTFChars(user_data_dir, 0);

    jint result = launch_chromium(arg1, arg2, (WebUILaunchOptions*)pOptions);

    env->ReleaseStringUTFChars(url, arg1);
    env->ReleaseStringUTFChars(user_data_dir, arg2);

    return result;
}

struct EnumWindowsData1 {
    DWORD pid;
    HWND hWnd;
};

// 回调函数：检查每个窗口属于哪个进程
BOOL CALLBACK EnumWinProc1(HWND hWnd, LPARAM lParam) {
    EnumWindowsData1* data = (EnumWindowsData1*)lParam;

    DWORD processId;
    GetWindowThreadProcessId(hWnd, &processId);

    // 还需要判断是否是主窗口（通常有标题栏且可见）
    if (processId == data->pid && GetParent(hWnd) == NULL && IsWindowVisible(hWnd)) {
        data->hWnd = hWnd;
        return FALSE; // 找到后停止遍历
    }
    return TRUE;
}

JNIEXPORT jlong JNICALL Java_roj_webui_WebUI_bindWindow0(JNIEnv* env, jclass, jlong pid) {
    EnumWindowsData1 data = { (DWORD)pid, NULL };
    EnumWindows(EnumWinProc1, (LPARAM)&data);
    return (jlong)data.hWnd;
}
