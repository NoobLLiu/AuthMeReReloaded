#!/bin/bash
# ============================================================
# AuthMe + Geyser Extension 一键编译脚本
# 用法: ./build.sh [all|authme|extension]
#   all       - 编译 AuthMe 插件和 Geyser 扩展（默认）
#   authme    - 仅编译 AuthMe 插件
#   extension - 仅编译 Geyser 扩展
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AUTHME_DIR="$SCRIPT_DIR"
EXTENSION_DIR="$SCRIPT_DIR/authme-geyser-extension"
OUTPUT_DIR="$SCRIPT_DIR/dist"

TARGET="${1:-all}"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

build_authme() {
    info "========== 编译 AuthMe 插件 =========="
    cd "$AUTHME_DIR"
    mvn -DskipTests package -q
    mkdir -p "$OUTPUT_DIR"
    cp -f target/AuthMe-*.jar "$OUTPUT_DIR/" 2>/dev/null || true
    info "AuthMe 插件编译完成 -> $OUTPUT_DIR/"
    ls -lh "$OUTPUT_DIR"/AuthMe-*.jar 2>/dev/null
}

build_extension() {
    info "========== 编译 Geyser 扩展 =========="
    cd "$EXTENSION_DIR"
    mvn -DskipTests package -q
    mkdir -p "$OUTPUT_DIR"
    cp -f target/authme-geyser-extension-*.jar "$OUTPUT_DIR/" 2>/dev/null || true
    info "Geyser 扩展编译完成 -> $OUTPUT_DIR/"
    ls -lh "$OUTPUT_DIR"/authme-geyser-extension-*.jar 2>/dev/null
    info "部署: 将 authme-geyser-extension-1.0.0.jar 复制到 Geyser 的 extensions/ 目录"
}

case "$TARGET" in
    all)
        build_authme
        echo ""
        build_extension
        ;;
    authme)
        build_authme
        ;;
    extension)
        build_extension
        ;;
    *)
        error "未知目标: $TARGET"
        echo "用法: $0 [all|authme|extension]"
        exit 1
        ;;
esac

echo ""
info "========== 全部完成 =========="
