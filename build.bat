@echo off
REM ============================================================
REM AuthMe + Geyser Extension 一键编译脚本 (Windows)
REM 用法: build.bat [all|authme|extension]
REM ============================================================

setlocal enabledelayedexpansion

set TARGET=%1
if "%TARGET%"=="" set TARGET=all

set SCRIPT_DIR=%~dp0
set AUTHME_DIR=%SCRIPT_DIR%
set EXTENSION_DIR=%SCRIPT_DIR%authme-geyser-extension
set OUTPUT_DIR=%SCRIPT_DIR%dist

if "%TARGET%"=="all" goto :build_all
if "%TARGET%"=="authme" goto :build_authme
if "%TARGET%"=="extension" goto :build_extension
echo [ERROR] 未知目标: %TARGET%
echo 用法: build.bat [all|authme|extension]
exit /b 1

:build_all
call :build_authme
echo.
call :build_extension
echo.
echo [INFO] ========== 全部完成 ==========
goto :eof

:build_authme
echo [INFO] ========== 编译 AuthMe 插件 ==========
cd /d "%AUTHME_DIR%"
call mvn -DskipTests package -q
if errorlevel 1 (
    echo [ERROR] AuthMe 编译失败
    exit /b 1
)
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
copy /y target\AuthMe-*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo [INFO] AuthMe 插件编译完成 -^> %OUTPUT_DIR%\
goto :eof

:build_extension
echo [INFO] ========== 编译 Geyser 扩展 ==========
cd /d "%EXTENSION_DIR%"
call mvn -DskipTests package -q
if errorlevel 1 (
    echo [ERROR] Geyser 扩展编译失败
    exit /b 1
)
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
copy /y target\authme-geyser-extension-*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo [INFO] Geyser 扩展编译完成 -^> %OUTPUT_DIR%\
echo [INFO] 部署: 将 authme-geyser-extension-1.0.0.jar 复制到 Geyser 的 extensions\ 目录
goto :eof
