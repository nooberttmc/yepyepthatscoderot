/* VOIDBEAT.exe - starts the game with the Java runtime bundled next to it. */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE h, HINSTANCE p, PWSTR args, int show) {
    wchar_t dir[MAX_PATH], javaw[MAX_PATH], jar[MAX_PATH], cmd[4 * MAX_PATH];
    GetModuleFileNameW(NULL, dir, MAX_PATH);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;
    swprintf(javaw, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", dir);
    swprintf(jar, MAX_PATH, L"%ls\\app\\voidbeat.jar", dir);
    if (GetFileAttributesW(javaw) == INVALID_FILE_ATTRIBUTES || GetFileAttributesW(jar) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"Couldn't find the 'runtime' or 'app' folder next to VOIDBEAT.exe.\n\n"
                          L"Extract the whole zip (don't run the exe from inside the zip) and try again.",
                    L"VOIDBEAT", MB_ICONERROR);
        return 1;
    }
    swprintf(cmd, 4 * MAX_PATH, L"\"%ls\" -Xmx1g -Dsun.java2d.d3d=true -jar \"%ls\" %ls", javaw, jar, args ? args : L"");
    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Couldn't start the bundled Java runtime.", L"VOIDBEAT", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
