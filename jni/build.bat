@echo off
set MINGW_DIR=D:\priv\app\llvm-mingw
%MINGW_DIR%\bin\clang -I"%JAVA_HOME%include" -I"%JAVA_HOME%include\win32" -I"libdivsufsort\include" -maes -msse4 -O3 -flto=full -ffunction-sections -fdata-sections -nodefaultlibs -pipe main.cpp -c -o main.obj -D_WIN32_WINNT=0x602
%MINGW_DIR%\bin\ld.lld -O3 -icf=all -m i386pep --shared -Bdynamic -e DllMain --enable-auto-image-base --disable-runtime-pseudo-reloc --gc-sections -o ..\core\resources\libOmniJni.dll -s main.obj libdivsufsort/lib/libdivsufsort.a -L%MINGW_DIR%/x86_64-w64-mingw32/lib -lmsvcrt -lws2_32 -lkernel32 -luser32 -lcomdlg32 -lole32 -lshell32 -ladvapi32
del main.obj
pause