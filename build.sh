#!/bin/bash

# 构建 Android Release APK (armv8 和 v7a)
# 使用方法: ./build.sh

set -e  # 遇到错误立即退出

echo "🚀 开始构建 Android Release 版本..."

# 颜色定义
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 检查是否在项目根目录
if [ ! -f "package.json" ]; then
    echo -e "${RED}❌ 错误: 请在项目根目录运行此脚本${NC}"
    exit 1
fi

# 清理旧的构建产物
echo -e "${BLUE}🧹 清理旧的构建产物...${NC}"
rm -rf android/app/build/outputs/apk/release/*.apk

# 清理 CMake 缓存
echo -e "${BLUE}🧹 清理 CMake 缓存...${NC}"
rm -rf android/app/.cxx

# 进入 android 目录
cd android

# 清理 Gradle 缓存（不使用 clean，避免删除 codegen）
echo -e "${BLUE}🧹 清理构建缓存...${NC}"
./gradlew cleanBuildCache || true

# 生成 codegen 文件
echo -e "${BLUE}📝 生成 React Native Codegen 文件...${NC}"
./gradlew generateCodegenArtifactsFromSchema

# 构建 Release APK
echo -e "${BLUE}🔨 开始构建 Release APK...${NC}"
./gradlew assembleRelease

# 返回项目根目录
cd ..

# 检查构建产物
APK_DIR="android/app/build/outputs/apk/release"
if [ -d "$APK_DIR" ]; then
    echo -e "${GREEN}✅ 构建成功!${NC}"
    echo -e "${GREEN}📦 APK 文件位置:${NC}"
    ls -lh "$APK_DIR"/*.apk
    
    # 创建输出目录
    OUTPUT_DIR="build-output"
    mkdir -p "$OUTPUT_DIR"
    
    # 复制 APK 到输出目录并重命名
    echo -e "${BLUE}📋 复制 APK 到 $OUTPUT_DIR 目录...${NC}"
    cp "$APK_DIR"/*.apk "$OUTPUT_DIR/"
    
    echo -e "${GREEN}✅ 所有构建产物已复制到 $OUTPUT_DIR 目录${NC}"
    echo -e "${GREEN}📦 最终产物:${NC}"
    ls -lh "$OUTPUT_DIR"/*.apk
else
    echo -e "${RED}❌ 构建失败: 未找到 APK 文件${NC}"
    exit 1
fi

echo -e "${GREEN}🎉 构建完成!${NC}"
