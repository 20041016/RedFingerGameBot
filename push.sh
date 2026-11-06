
#!/system/bin/sh

# ==========================================
# RedFingerGameBot 自动推送及 APK 下载
# MT 管理器专用 Release 正式签名版
# ==========================================

PROJECT_DIR="/storage/emulated/0/RedFingerGameBot_V0_9_3"
DOWNLOAD_DIR="/storage/emulated/0/Download"
OUTPUT_DIR="/storage/emulated/0/测试"

REPO="20041016/RedFingerGameBot"
BRANCH="main"
ARTIFACT_NAME="RedFingerGameBot-APK"
WORKFLOW_NAME="Build RedFinger GameBot APK"

TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

# 强制使用 MT 管理器自带 curl
CURL="/data/user/0/bin.mt.plus/files/term/bin/curl"

export HOME="$TERMUX_HOME"
export PATH="$TERMUX_BIN:/system/bin:/system/xbin:$PATH"
export GIT_TERMINAL_PROMPT=0

# 临时目录
TEMP_DIR="$PROJECT_DIR/.apk_download"
ARTIFACT_ZIP="$TEMP_DIR/artifact.zip"
EXTRACT_DIR="$TEMP_DIR/extracted"

# 最终 APK
DEST="$OUTPUT_DIR/RedFingerGameBot.apk"

echo "======================================"
echo " RedFingerGameBot Release 自动构建下载"
echo "======================================"

# ==========================================
# 临时文件清理函数
# ==========================================

cleanup() {
    echo "[清理] 正在删除临时下载文件..."
    rm -rf "$TEMP_DIR"
    echo "[清理] 临时文件清理完成"
}

# 异常退出时也尝试清理
trap cleanup EXIT HUP INT TERM

# ==========================================
# 1. 检查工具
# ==========================================

echo "[检查] 正在检查运行环境..."

for cmd in git jq unzip; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[错误] 缺少工具：$cmd"
        exit 1
    fi
done

GIT="$(command -v git)"

if [ ! -x "$CURL" ]; then
    echo "[错误] 找不到 MT 管理器 curl：$CURL"
    exit 1
fi

if ! "$CURL" --version >/dev/null 2>&1; then
    echo "[错误] MT 管理器 curl 无法运行"
    exit 1
fi

echo "[成功] Git：$GIT"
echo "[成功] Curl：$CURL"

# ==========================================
# 2. 检查项目目录
# ==========================================

cd "$PROJECT_DIR" || {
    echo "[错误] 项目目录不存在"
    exit 1
}

if ! "$GIT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[错误] 不是 Git 仓库"
    exit 1
fi

mkdir -p "$OUTPUT_DIR" || exit 1

# ==========================================
# 3. 获取 GitHub Token
# ==========================================

echo "[1/7] 读取 GitHub 登录凭据..."

CREDENTIALS=$(
    printf 'protocol=https\nhost=github.com\nusername=20041016\n\n' |
    "$GIT" credential fill 2>/dev/null
)

TOKEN=$(printf '%s\n' "$CREDENTIALS" |
    sed -n 's/^password=//p' |
    head -n 1)

unset CREDENTIALS

if [ -z "$TOKEN" ]; then
    echo "[错误] 没有读取到 GitHub Token"
    echo "请先在 Termux 保存 GitHub HTTPS 登录凭据"
    exit 1
fi

API="https://api.github.com/repos/$REPO"

# ==========================================
# 4. 查找源码 ZIP
# ==========================================

echo "[2/7] 查找源码 ZIP..."

LATEST_ZIP=$(ls -t "$DOWNLOAD_DIR"/*.zip 2>/dev/null | head -n 1)

if [ -z "$LATEST_ZIP" ] || [ ! -f "$LATEST_ZIP" ]; then
    echo "[错误] Download 中没有 ZIP 文件"
    exit 1
fi

echo "源码：$(basename "$LATEST_ZIP")"

if ! unzip -t "$LATEST_ZIP" >/dev/null 2>&1; then
    echo "[错误] ZIP 文件损坏"
    exit 1
fi

ZIP_LIST=$(unzip -Z1 "$LATEST_ZIP" 2>/dev/null)

if ! printf '%s\n' "$ZIP_LIST" |
    grep -Eq '(^|/)settings\.gradle(\.kts)?$'; then
    echo "[错误] ZIP 缺少 settings.gradle"
    exit 1
fi

if ! printf '%s\n' "$ZIP_LIST" |
    grep -Eq '(^|/)app/build\.gradle(\.kts)?$'; then
    echo "[错误] ZIP 缺少 app/build.gradle"
    exit 1
fi

if ! printf '%s\n' "$ZIP_LIST" |
    grep -Eq '(^|/)app/src/'; then
    echo "[错误] ZIP 缺少 app/src 源码目录"
    exit 1
fi

unset ZIP_LIST

echo "[成功] 源码 ZIP 检查通过"

# ==========================================
# 5. 判断源码是否更新
# ==========================================

echo "[3/7] 检查 source.zip 是否更新..."

"$GIT" add -A || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Save local changes before update" || exit 1
fi

echo "同步 GitHub..."

"$GIT" pull --rebase origin "$BRANCH" || {
    echo "[错误] 同步失败，可能存在冲突"
    exit 1
}

cp -f "$LATEST_ZIP" "$PROJECT_DIR/source.zip" || exit 1

if "$GIT" diff --quiet -- source.zip; then

    echo "======================================"
    echo "[提示] source.zip 没有更新"
    echo "[模式] 下载最近一次成功构建的 APK"
    echo "======================================"

    DOWNLOAD_MODE="latest"

else

    echo "======================================"
    echo "[提示] 检测到 source.zip 更新"
    echo "[模式] 推送源码并构建新的 APK"
    echo "======================================"

    "$GIT" add source.zip || exit 1

    "$GIT" commit -m "Update source.zip: $(date '+%Y-%m-%d %H:%M:%S')" || exit 1

    COMMIT_SHA=$("$GIT" rev-parse HEAD)

    # ======================================
    # 6. 推送 GitHub
    # ======================================

    echo "[4/7] 推送 GitHub..."

    "$GIT" push origin "$BRANCH" || {
        echo "[错误] GitHub 推送失败"
        exit 1
    }

    echo "本次提交：$COMMIT_SHA"

    # ======================================
    # 7. 等待本次构建任务创建
    # ======================================

    echo "[5/7] 等待本次 GitHub Actions 构建..."

    RUN_ID=""
    COUNT=0

    while [ "$COUNT" -lt 160 ]; do

        RESPONSE=$("$CURL" -fsS \
            -H "Authorization: Bearer $TOKEN" \
            -H "Accept: application/vnd.github+json" \
            "$API/actions/runs?head_sha=$COMMIT_SHA&event=push&per_page=20") || {
                echo "[错误] 查询构建失败"
                exit 1
            }

        RUN_ID=$(printf '%s' "$RESPONSE" |
            jq -r \
            --arg sha "$COMMIT_SHA" \
            --arg name "$WORKFLOW_NAME" \
            '.workflow_runs[]? |
            select(
                .head_sha == $sha and
                .event == "push" and
                .name == $name
            ) |
            .id' | head -n 1)

        unset RESPONSE

        if [ -n "$RUN_ID" ]; then
            break
        fi

        COUNT=$((COUNT + 1))
        echo "等待构建任务创建... ($COUNT/160)"
        sleep 15
    done

    if [ -z "$RUN_ID" ]; then
        echo "[错误] 等待超时，没有找到本次构建"
        exit 1
    fi

    echo "构建编号：$RUN_ID"

    # ======================================
    # 8. 等待构建完成
    # ======================================

    COUNT=0

    while [ "$COUNT" -lt 160 ]; do

        RUN_INFO=$("$CURL" -fsS \
            -H "Authorization: Bearer $TOKEN" \
            -H "Accept: application/vnd.github+json" \
            "$API/actions/runs/$RUN_ID") || {
                echo "[错误] 获取构建状态失败"
                exit 1
            }

        STATUS=$(printf '%s' "$RUN_INFO" | jq -r '.status')
        CONCLUSION=$(printf '%s' "$RUN_INFO" | jq -r '.conclusion // empty')

        unset RUN_INFO

        echo "构建状态：$STATUS $CONCLUSION"

        if [ "$STATUS" = "completed" ]; then

            if [ "$CONCLUSION" != "success" ]; then

                echo "======================================"
                echo "[错误] GitHub Actions 构建失败：$CONCLUSION"
                echo "======================================"

                JOBS=$("$CURL" -fsS \
                    -H "Authorization: Bearer $TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    "$API/actions/runs/$RUN_ID/jobs?per_page=100")

                if [ -n "$JOBS" ]; then
                    printf '%s\n' "$JOBS" | jq -r '
                        .jobs[]? |
                        "Job: " + .name,
                        (.steps[]? |
                        select(.conclusion == "failure") |
                        "失败步骤：" + .name)
                    '
                fi

                unset JOBS
                exit 1
            fi

            break
        fi

        COUNT=$((COUNT + 1))
        sleep 15
    done

    if [ "$COUNT" -ge 160 ]; then
        echo "[错误] 构建等待超时"
        exit 1
    fi

fi

# ==========================================
# 9. 如果源码没有更新，查找最新成功构建
# ==========================================

if [ "$DOWNLOAD_MODE" = "latest" ]; then

    echo "[4/7] 查询最近一次成功构建..."

    RUNS=$("$CURL" -fsS \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$API/actions/runs?branch=$BRANCH&status=success&per_page=100") || {
            echo "[错误] 查询历史构建失败"
            exit 1
        }

    RUN_ID=$(printf '%s' "$RUNS" |
        jq -r \
        --arg name "$WORKFLOW_NAME" \
        --arg branch "$BRANCH" \
        '.workflow_runs[]? |
        select(
            .name == $name and
            .head_branch == $branch and
            .conclusion == "success"
        ) |
        .id' | head -n 1)

    unset RUNS

    if [ -z "$RUN_ID" ]; then
        echo "[错误] 没有找到最近一次成功的构建"
        exit 1
    fi

    echo "找到最近一次成功构建：$RUN_ID"

fi

# ==========================================
# 10. 下载 Artifact
# ==========================================

echo "[6/7] 下载构建产物..."

ARTIFACTS=$("$CURL" -fsS \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$API/actions/runs/$RUN_ID/artifacts") || {
        echo "[错误] 获取 Artifact 失败"
        exit 1
    }

ARTIFACT_ID=$(printf '%s' "$ARTIFACTS" |
    jq -r \
    --arg name "$ARTIFACT_NAME" \
    '.artifacts[]? |
    select(.name == $name and .expired == false) |
    .id' | head -n 1)

unset ARTIFACTS

if [ -z "$ARTIFACT_ID" ]; then
    echo "[错误] 没找到构建产物：$ARTIFACT_NAME"
    echo "可能该构建没有上传 APK，或者 Artifact 已过期"
    exit 1
fi

# ==========================================
# 11. 下载并解压
# ==========================================

echo "[7/7] 下载并解压 Release APK..."

# 清理之前可能残留的临时目录
rm -rf "$TEMP_DIR"
mkdir -p "$EXTRACT_DIR" || exit 1

"$CURL" -fL \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$API/actions/artifacts/$ARTIFACT_ID/zip" \
    -o "$ARTIFACT_ZIP" || {
        echo "[错误] 下载 Artifact 失败"
        exit 1
    }

unset TOKEN

if [ ! -s "$ARTIFACT_ZIP" ]; then
    echo "[错误] 下载的 Artifact 文件为空"
    exit 1
fi

if ! unzip -t "$ARTIFACT_ZIP" >/dev/null 2>&1; then
    echo "[错误] Artifact ZIP 损坏"
    exit 1
fi

unzip -o "$ARTIFACT_ZIP" \
    -d "$EXTRACT_DIR" >/dev/null || {
        echo "[错误] Artifact 解压失败"
        exit 1
    }

# 查找 APK
APK=$(find "$EXTRACT_DIR" \
    -type f -name "app-release.apk" -print -quit)

if [ -z "$APK" ] || [ ! -s "$APK" ]; then
    echo "[错误] 构建产物中没有找到有效的 app-release.apk"
    exit 1
fi

# ==========================================
# 12. 安全复制 APK
# ==========================================

echo "[复制] 正在安装包到目标目录..."

NEW_APK="$OUTPUT_DIR/RedFingerGameBot.new.apk"

rm -f "$NEW_APK"

cp -f "$APK" "$NEW_APK" || {
    echo "[错误] APK 复制失败"
    exit 1
}

if [ ! -s "$NEW_APK" ]; then
    echo "[错误] 新 APK 文件为空"
    rm -f "$NEW_APK"
    exit 1
fi

# 新文件复制成功后再替换旧文件
mv -f "$NEW_APK" "$DEST" || {
    echo "[错误] 替换最终 APK 失败"
    rm -f "$NEW_APK"
    exit 1
}

if [ ! -s "$DEST" ]; then
    echo "[错误] 最终 APK 检查失败"
    exit 1
fi

# ==========================================
# 13. 清理临时文件
# ==========================================

echo "[清理] 删除 Artifact 压缩包..."

rm -f "$ARTIFACT_ZIP"

echo "[清理] 删除临时解压目录..."

rm -rf "$EXTRACT_DIR"

# 删除空临时目录
rmdir "$TEMP_DIR" 2>/dev/null || true

echo "[成功] 临时文件已清理"

# ==========================================
# 14. 完成
# ==========================================

echo "======================================"
echo "          全部任务完成！"
echo "======================================"

if [ "$DOWNLOAD_MODE" = "latest" ]; then
    echo "运行模式：下载最近一次成功构建"
else
    echo "运行模式：上传新源码并构建"
fi

echo "APK 位置：$DEST"
echo "构建编号：$RUN_ID"

if [ -n "$COMMIT_SHA" ]; then
    echo "提交 SHA：$COMMIT_SHA"
fi

echo "APK 类型：Release 正式签名版"
echo "临时文件：已删除"

exit 0
