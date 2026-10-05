//
// Created by Roj234 on 2025/11/27 0027.
//

#include <windows.h>
#include <aclapi.h>

BOOL EnablePrivilege(LPCSTR privilege, BOOL enable) {
    HANDLE hToken;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, &hToken)) {
        return FALSE;
    }

    TOKEN_PRIVILEGES tp;
    LUID luid;
    if (!LookupPrivilegeValueA(NULL, privilege, &luid)) {
        CloseHandle(hToken);
        return FALSE;
    }

    tp.PrivilegeCount = 1;
    tp.Privileges[0].Luid = luid;
    tp.Privileges[0].Attributes = enable ? SE_PRIVILEGE_ENABLED : 0;

    BOOL result = AdjustTokenPrivileges(hToken, FALSE, &tp, sizeof(tp), NULL, NULL);
    CloseHandle(hToken);
    return result && (GetLastError() != ERROR_NOT_ALL_ASSIGNED);
}

JNIEXPORT jbyteArray JNICALL Java_roj_io_SecurityDescriptor_get0(JNIEnv* env, jclass, jstring path, jboolean admin) {
    SECURITY_INFORMATION si;

    if (admin && EnablePrivilege(SE_BACKUP_NAME, TRUE)) {
        // 安全描述符的所有部分。 这对于需要保留整个安全描述符的备份和还原软件非常有用。
        // 要求: > Windows 7
        // https://learn.microsoft.com/zh-cn/windows/win32/secauthz/security-information
        si = BACKUP_SECURITY_INFORMATION;
    } else {
        si = OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION;
    }

    const jchar* widePath = env->GetStringChars(path, 0);
    LPCWSTR lpFileName =reinterpret_cast<LPCWSTR>(widePath);

    DWORD absSize;
    GetFileSecurityW(lpFileName, si, NULL, 0, &absSize);

    // 为什么不用malloc呢？
    auto pAbsSD = (PSECURITY_DESCRIPTOR)LocalAlloc(LPTR, absSize);
    if (!pAbsSD) {
        env->ReleaseStringChars(path, widePath);
        return NULL;
    }

    if (!GetFileSecurityW(lpFileName, si, pAbsSD, absSize, &absSize)) {
        LocalFree(pAbsSD);
        env->ReleaseStringChars(path, widePath);
        Error(env, "Insufficient permission");
        return NULL;
    }

    env->ReleaseStringChars(path, widePath);

    // 转换为自相对 SD
    DWORD relSize;
    MakeSelfRelativeSD(pAbsSD, NULL, &relSize);

    auto pSelfRelSD = (PSECURITY_DESCRIPTOR)LocalAlloc(LPTR, relSize);
    if (!pSelfRelSD) {
        LocalFree(pAbsSD);
        return NULL;
    }

    if (!MakeSelfRelativeSD(pAbsSD, pSelfRelSD, &relSize)) {
        LocalFree(pSelfRelSD);
        LocalFree(pAbsSD);
        return NULL;
    }
    LocalFree(pAbsSD);

    jbyteArray result = env->NewByteArray(relSize);
    if (result) {
        env->SetByteArrayRegion(result, 0, relSize, (jbyte*)pSelfRelSD);
    }

    LocalFree(pSelfRelSD);
    return result;
}

JNIEXPORT jboolean JNICALL Java_roj_io_SecurityDescriptor_set0(JNIEnv* env, jclass, jstring path, jlong sd, jboolean admin) {
    SECURITY_INFORMATION si;

    if (admin && EnablePrivilege(SE_RESTORE_NAME, TRUE)) {
        si = BACKUP_SECURITY_INFORMATION;
    } else {
        si = OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION;
    }

    const jchar* widePath = env->GetStringChars(path, 0);
    LPCWSTR lpFileName =reinterpret_cast<LPCWSTR>(widePath);
    BOOL result = SetFileSecurityW(lpFileName, si, (PSECURITY_DESCRIPTOR)sd);
    env->ReleaseStringChars(path, widePath);
    return result;
}