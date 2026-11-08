#!/system/bin/sh

# ==========================================
# RedFingerGameBot 自动推送及 APK 下载
# MT 管理器专用 Release 正式签名版
# 方案：源码 SHA 比对触发构建 + Artifact 多线程下载
# ==========================================

PROJECT_DIR="/storage/emulated/0/RedFingerGameBot_V0_9_3"
DOWNLOAD_DIR="/storage/emulated/0/Download"
OUTPUT_DIR="/storage/emulated/0/测试"

REPO="20041016/RedFingerGameBot"
DEFAULT_BRANCH="main"
ARTIFACT_NAME="RedFingerGameBot-APK"
WORKFLOW_NAME="Build RedFinger GameBot APK"

TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

# 优先 MT 管理器 curl，fallback Termux curl
CURL="/data/user/0/bin.mt.plus/files/term/bin/curl"

export HOME="$TERMUX_HOME"
export PATH="$TERMUX_BIN:/system/bin:/system/xbin:$PATH"
export GIT_TERMINAL_PROMPT=0

# 临时目录
TEMP_DIR="$PROJECT_DIR/.apk_download"
ARTIFACT_ZIP="$TEMP_DIR/artifact.zip"
EXTRACT_DIR="$TEMP_DIR/extracted"
RUNS_FILE="$TEMP_DIR/runs.json"

# 最终 APK
DEST="$OUTPUT_DIR/RedFingerGameBot.apk"

# curl 通用选项（API 查询用，短超时）
CURL_API_OPTS="--connect-timeout 10 --max-time 60 --retry 2 --retry-delay 2"

# 状态变量
DOWNLOAD_MODE=""
COMMIT_SHA=""
RUN_ID=""
BRANCH=""

echo "======================================"
echo " RedFingerGameBot Release 自动构建下载"
echo "======================================"

# ==========================================
# 临时文件清理
# ==========================================

cleanup() {
    echo ""
    echo "[清理] 正在删除临时下载文件..."
    rm -rf "$TEMP_DIR"
    echo "[清理] 临时文件清理完成"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# ==========================================
# 1. 检查工具
# ==========================================

echo "[检查] 正在检查运行环境..."

for cmd in git jq unzip sha256sum grep sed head awk date sleep find; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[错误] 缺少工具：$cmd"
        exit 1
    fi
done

GIT="$(command -v git)"

# curl：优先 MT 管理器，其次 Termux
if [ -x "$CURL" ] && "$CURL" --version >/dev/null 2>&1; then
    :
else
    CURL="$(command -v curl 2>/dev/null)"
fi

if [ -z "$CURL" ] || [ ! -x "$CURL" ] || ! "$CURL" --version >/dev/null 2>&1; then
    echo "[错误] 找不到可用的 curl（MT 管理器或 Termux）"
    exit 1
fi

# aria2 可选，有就用
if command -v aria2c >/dev/null 2>&1; then
    HAVE_ARIA2=1
    echo "[成功] aria2c：$(command -v aria2c)（多线程下载）"
else
    HAVE_ARIA2=0
    echo "[提示] 未安装 aria2c，将使用 curl 单线程（pkg install aria2 可提速）"
fi

# unzip 必须支持 -Z（zipinfo）
if ! unzip -Z >/dev/null 2>&1; then
    echo "[错误] 当前 unzip 不支持 -Z，请安装完整版 unzip"
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

# 探测当前分支
BRANCH=$("$GIT" symbolic-ref --short HEAD 2>/dev/null)
[ -n "$BRANCH" ] || BRANCH="$DEFAULT_BRANCH"

mkdir -p "$OUTPUT_DIR" || exit 1
mkdir -p "$TEMP_DIR" || exit 1

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
    echo "请先在 Termux 保存 GitHub HTTPS 登录凭据，例如："
    echo "  git config --global credential.helper store"
    echo "  git push   # 手动输入一次用户名和 token"
    exit 1
fi

API="https://api.github.com/repos/$REPO"

# ==========================================
# 4. 查找 Download 中最新 ZIP
# ==========================================

echo "[2/7] 查找源码 ZIP..."

LATEST_ZIP=$(ls -t "$DOWNLOAD_DIR"/*.zip 2>/dev/null | head -n 1)

if [ -z "$LATEST_ZIP" ] || [ ! -f "$LATEST_ZIP" ]; then
    echo "[错误] Download 中没有 ZIP 文件"
    exit 1
fi

echo "======================================"
echo "选中的源码压缩包："
echo "$LATEST_ZIP"
echo "文件大小：$(du -h "$LATEST_ZIP" | awk '{print $1}')"

NEW_SHA=$(sha256sum "$LATEST_ZIP" | awk '{print $1}')

echo "SHA256：$NEW_SHA"
echo "======================================"

# 检查 ZIP 完整性
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
# 5. 同步 GitHub 并比较源码
# ==========================================

echo "[3/7] 同步 GitHub 并检查源码变化..."

"$GIT" add -A || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Save local changes before update" || exit 1
fi

echo "[同步] 正在同步 GitHub..."

if ! "$GIT" pull --rebase origin "$BRANCH"; then
    echo "[错误] 同步失败，可能存在冲突"
    echo "请手动执行："
    echo "  cd \"$PROJECT_DIR\""
    echo "  git rebase --abort"
    exit 1
fi

if [ -f "$PROJECT_DIR/source.zip" ]; then
    OLD_SHA=$(sha256sum "$PROJECT_DIR/source.zip" | awk '{print $1}')
else
    OLD_SHA=""
fi

echo "======================================"
echo "新 ZIP SHA256：$NEW_SHA"
echo "旧 ZIP SHA256：$OLD_SHA"
echo "======================================"

if [ "$NEW_SHA" = "$OLD_SHA" ] && [ -n "$OLD_SHA" ]; then

    echo "======================================"
    echo "[提示] source.zip 内容没有变化"
    echo "[模式] 下载最近一次成功构建的 APK"
    echo "======================================"

    DOWNLOAD_MODE="latest"

else

    echo "======================================"
    echo "[提示] 检测到新的源码压缩包"
    echo "[模式] 推送源码并构建新的 APK"
    echo "======================================"

    cp -f "$LATEST_ZIP" "$PROJECT_DIR/source.zip" || {
        echo "[错误] 复制 source.zip 失败"
        exit 1
    }

    "$GIT" add source.zip || exit 1

    if "$GIT" diff --cached --quiet; then
        echo "[提示] source.zip 无实际变化，跳过提交"
        COMMIT_SHA=$("$GIT" rev-parse HEAD)
        DOWNLOAD_MODE="latest"
    else
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

            RESPONSE=$("$CURL" $CURL_API_OPTS -fsS \
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

        while [ "$COUNT" -lt 240 ]; do

            RUN_INFO=$("$CURL" $CURL_API_OPTS -fsS \
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

                    JOBS=$("$CURL" $CURL_API_OPTS -fsS \
                        -H "Authorization: Bearer $TOKEN" \
                        -H "Accept: application/vnd.github+json" \
                        "$API/actions/runs/$RUN_ID/jobs?per_page=100")

                    if [ -n "$JOBS" ]; then
                        printf '%s\n' "$JOBS" | jq -r '
                            .jobs[]? |
                            "Job: " + .name,
                            (.steps[]? |
                            select(.conclusion == "failure") |
                            "  失败步骤：" + .name)
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

        if [ "$COUNT" -ge 240 ]; then
            echo "[错误] 构建等待超时"
            exit 1
        fi
    fi
fi

# ==========================================
# 9. 如果源码没有更新，查找最近成功构建
# ==========================================

if [ "$DOWNLOAD_MODE" = "latest" ]; then

    echo "[4/7] 查询最近一次成功构建..."

    # 写入临时文件，避免 ARG_MAX（Argument list too long）
    if ! "$CURL" $CURL_API_OPTS -fsS \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$API/actions/runs?branch=$BRANCH&per_page=30" \
        -o "$RUNS_FILE"; then
        echo "[错误] 查询历史构建失败"
        exit 1
    fi

    RUN_ID=$(jq -r \
        --arg name "$WORKFLOW_NAME" \
        --arg branch "$BRANCH" \
        '.workflow_runs[]? |
        select(
            .name == $name and
            .head_branch == $branch and
            .conclusion == "success"
        ) |
        .id' "$RUNS_FILE" | head -n 1)

    if [ -z "$RUN_ID" ]; then
        echo "[错误] 没有找到最近一次成功的构建"
        echo "最近的构建列表（供排查）："
        jq -r '.workflow_runs[]? |
            "\(.id)  \(.name)  \(.head_branch)  \(.status)/\(.conclusion // "-")"' \
            "$RUNS_FILE" | head -n 20
        rm -f "$RUNS_FILE"
        exit 1
    fi

    rm -f "$RUNS_FILE"

    echo "找到最近一次成功构建：$RUN_ID"

fi

# ==========================================
# 10. 获取 Artifact 列表
# ==========================================

echo "[5/7] 获取构建产物列表..."

ARTIFACTS=$("$CURL" $CURL_API_OPTS -fsS \
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

if [ -z "$ARTIFACT_ID" ]; then
    echo "[错误] 没找到构建产物：$ARTIFACT_NAME"
    echo "可用 artifacts："
    printf '%s' "$ARTIFACTS" |
        jq -r '.artifacts[]? | "\(.id)  \(.name)  expired=\(.expired)"'
    unset ARTIFACTS
    exit 1
fi

unset ARTIFACTS

echo "Artifact ID：$ARTIFACT_ID"

# ==========================================
# 11. 下载 Artifact（aria2 多线程 + 镜像轮询）
# ==========================================

echo "[6/7] 下载 Artifact..."

ARTIFACT_URL="$API/actions/artifacts/$ARTIFACT_ID/zip"

# 先拿 302 的真实下载地址（objects.githubusercontent.com，带签名）
REAL_URL=$("$CURL" -fsS -o /dev/null -w '%{redirect_url}' \
    --connect-timeout 10 --max-time 30 \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$ARTIFACT_URL" 2>/dev/null)

if [ -z "$REAL_URL" ]; then
    echo "[警告] 未拿到 302 地址，回退官方 curl 下载"
    rm -f "$ARTIFACT_ZIP"
    "$CURL" -fL --connect-timeout 10 --max-time 600 \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$ARTIFACT_URL" -o "$ARTIFACT_ZIP" || {
            echo "[错误] 官方下载失败"
            exit 1
        }
else
    echo "真实地址：$REAL_URL"

    # 候选：直连 + 镜像（空格分隔，URL 本身无空格）
    CANDIDATES="
$REAL_URL
https://gh-proxy.com/$REAL_URL
https://ghfast.top/$REAL_URL
https://ghproxy.net/$REAL_URL
"

    DL_START=$(date +%s)
    DL_OK=0

    for url in $CANDIDATES; do
        [ -n "$url" ] || continue
        echo "[下载] 尝试：$url"

        rm -f "$ARTIFACT_ZIP"

        if [ "$HAVE_ARIA2" -eq 1 ]; then
            aria2c -x 8 -s 8 -k 1M \
                --connect-timeout=10 --timeout=30 \
                --max-tries=2 --retry-wait=2 \
                --allow-overwrite=true \
                --console-log-level=warn \
                -d "$TEMP_DIR" -o "$(basename "$ARTIFACT_ZIP")" \
                "$url" >/dev/null 2>&1
        else
            "$CURL" -fL --connect-timeout 10 --max-time 300 \
                "$url" -o "$ARTIFACT_ZIP" >/dev/null 2>&1
        fi

        # 校验：非空 + zip 结构完整（防镜像返回 HTML 错误页）
        if [ -s "$ARTIFACT_ZIP" ] && unzip -t "$ARTIFACT_ZIP" >/dev/null 2>&1; then
            echo "[下载] 成功"
            DL_OK=1
            break
        else
            echo "[下载] 失败，换下一个"
        fi
    done

    if [ "$DL_OK" -ne 1 ]; then
        echo "[警告] 所有下载源失败，回退官方 curl"
        rm -f "$ARTIFACT_ZIP"
        "$CURL" -fL --connect-timeout 10 --max-time 600 \
            -H "Authorization: Bearer $TOKEN" \
            -H "Accept: application/vnd.github+json" \
            "$ARTIFACT_URL" -o "$ARTIFACT_ZIP" || {
                echo "[错误] 官方下载也失败"
                exit 1
            }
    fi

    DL_END=$(date +%s)
    echo "[下载] 耗时：$((DL_END - DL_START)) 秒，大小：$(du -h "$ARTIFACT_ZIP" | awk '{print $1}')"
fi

unset TOKEN

if [ ! -s "$ARTIFACT_ZIP" ]; then
    echo "[错误] 下载的 Artifact 文件为空"
    exit 1
fi

if ! unzip -t "$ARTIFACT_ZIP" >/dev/null 2>&1; then
    echo "[错误] Artifact ZIP 损坏"
    exit 1
fi

# ==========================================
# 12. 解压并查找 APK
# ==========================================

echo "[7/7] 解压 APK..."

rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR" || exit 1

unzip -o "$ARTIFACT_ZIP" \
    -d "$EXTRACT_DIR" >/dev/null || {
        echo "[错误] Artifact 解压失败"
        exit 1
    }

# 宽松查找 APK，排除 unsigned
APK=$(find "$EXTRACT_DIR" \
    -type f -name "*.apk" ! -name "*unsigned*" -print -quit)

if [ -z "$APK" ] || [ ! -s "$APK" ]; then
    echo "[错误] 构建产物中没有找到有效的 APK"
    echo "解压内容："
    find "$EXTRACT_DIR" -type f -name "*.apk" -print 2>/dev/null
    exit 1
fi

echo "[成功] 找到 APK：$(basename "$APK")"

# ==========================================
# 13. 安全复制 APK
# ==========================================

echo "[复制] 正在复制 APK 到目标目录..."

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
# 14. 清理临时文件
# ==========================================

echo "[清理] 删除 Artifact 压缩包..."
rm -f "$ARTIFACT_ZIP"

echo "[清理] 删除临时解压目录..."
rm -rf "$EXTRACT_DIR"

rmdir "$TEMP_DIR" 2>/dev/null || true

echo "[成功] 临时文件已清理"

# ==========================================
# 15. 完成
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

if [ -n "$RUN_ID" ]; then
    echo "构建编号：$RUN_ID"
fi

if [ -n "$COMMIT_SHA" ]; then
    echo "提交 SHA：$COMMIT_SHA"
fi

echo "APK 类型：Release 正式签名版"
echo "临时文件：已删除"

exit 0