
#!/system/bin/sh

PROJECT_DIR="/storage/emulated/0/RedFingerGameBot_V0_9_3"
DOWNLOAD_DIR="/storage/emulated/0/Download"
OUTPUT_DIR="/storage/emulated/0/测试"

REPO="20041016/RedFingerGameBot"
BRANCH="main"
ARTIFACT_NAME="RedFingerGameBot-APK"

TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

export HOME="$TERMUX_HOME"
export PATH="$TERMUX_BIN:/system/bin:/system/xbin:$PATH"
export GIT_TERMINAL_PROMPT=0

echo "======================================"
echo " RedFingerGameBot 自动推送及 APK 下载"
echo "======================================"

# 检查工具
for cmd in git curl jq unzip; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[错误] 缺少工具：$cmd"
        exit 1
    fi
done

GIT="$(command -v git)"

cd "$PROJECT_DIR" || exit 1

if ! "$GIT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[错误] 不是 Git 仓库"
    exit 1
fi

mkdir -p "$OUTPUT_DIR" || exit 1

# 获取 GitHub Token，不显示 Token
echo "[1/7] 读取 GitHub 登录凭据..."

CREDENTIALS=$(printf 'protocol=https\nhost=github.com\nusername=20041016\n\n' | "$GIT" credential fill 2>/dev/null)

TOKEN=$(printf '%s\n' "$CREDENTIALS" | sed -n 's/^password=//p' | head -n 1)

unset CREDENTIALS

if [ -z "$TOKEN" ]; then
    echo "[错误] 没有读取到已保存的 GitHub Token"
    echo "请先在 Termux 成功进行一次 GitHub HTTPS 认证并保存凭据。"
    exit 1
fi

# 查找最新 ZIP
echo "[2/7] 查找源码 ZIP..."

LATEST_ZIP=$(ls -t "$DOWNLOAD_DIR"/*.zip 2>/dev/null | head -n 1)

if [ -z "$LATEST_ZIP" ] || [ ! -f "$LATEST_ZIP" ]; then
    echo "[错误] Download 中没有 ZIP"
    exit 1
fi

echo "源码：$(basename "$LATEST_ZIP")"

unzip -t "$LATEST_ZIP" >/dev/null 2>&1 || {
    echo "[错误] ZIP 文件损坏"
    exit 1
}

# 保存本地已有修改
echo "[3/7] 保存本地修改..."

"$GIT" add -A || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Save local changes before update" || exit 1
fi

# 同步远程
echo "同步 GitHub..."

"$GIT" pull --rebase origin "$BRANCH" || {
    echo "[错误] 同步失败，可能存在冲突"
    exit 1
}

# 替换源码 ZIP
cp -f "$LATEST_ZIP" "$PROJECT_DIR/source.zip" || exit 1

if "$GIT" diff --quiet -- source.zip; then
    echo "[提示] source.zip 没有变化，本次不会触发新的构建。"
    exit 1
fi

"$GIT" add source.zip || exit 1

"$GIT" commit -m "Update source.zip: $(date '+%Y-%m-%d %H:%M:%S')" || exit 1

COMMIT_SHA=$("$GIT" rev-parse HEAD)

# 推送
echo "[4/7] 推送 GitHub..."

"$GIT" push origin "$BRANCH" || exit 1

echo "本次提交：$COMMIT_SHA"

# 查询本次提交对应的构建
API="https://api.github.com/repos/$REPO"

echo "[5/7] 等待 GitHub Actions 构建..."

RUN_ID=""
COUNT=0

while [ "$COUNT" -lt 160 ]; do
    RESPONSE=$(curl -fsS \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$API/actions/runs?head_sha=$COMMIT_SHA&event=push&per_page=10") || {
            echo "[错误] 查询构建失败"
            exit 1
        }

    RUN_ID=$(printf '%s' "$RESPONSE" | jq -r \
        --arg sha "$COMMIT_SHA" \
        '.workflow_runs[]? | select(.head_sha == $sha and .event == "push") | .id' | head -n 1)

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

COUNT=0

while [ "$COUNT" -lt 160 ]; do
    RUN_INFO=$(curl -fsS \
        -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" \
        "$API/actions/runs/$RUN_ID") || exit 1

    STATUS=$(printf '%s' "$RUN_INFO" | jq -r '.status')
    CONCLUSION=$(printf '%s' "$RUN_INFO" | jq -r '.conclusion // empty')

    unset RUN_INFO

    echo "构建状态：$STATUS ${CONCLUSION}"

    if [ "$STATUS" = "completed" ]; then
        if [ "$CONCLUSION" != "success" ]; then
            echo "[错误] GitHub Actions 构建失败：$CONCLUSION"
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

# 获取 Artifact
echo "[6/7] 下载构建产物..."

ARTIFACTS=$(curl -fsS \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$API/actions/runs/$RUN_ID/artifacts") || exit 1

ARTIFACT_ID=$(printf '%s' "$ARTIFACTS" | jq -r \
    --arg name "$ARTIFACT_NAME" \
    '.artifacts[]? | select(.name == $name and .expired == false) | .id' | head -n 1)

unset ARTIFACTS

if [ -z "$ARTIFACT_ID" ]; then
    echo "[错误] 没找到构建产物：$ARTIFACT_NAME"
    exit 1
fi

TEMP_DIR="$PROJECT_DIR/.apk_download"
mkdir -p "$TEMP_DIR" || exit 1

ARTIFACT_ZIP="$TEMP_DIR/artifact.zip"

curl -fL \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "$API/actions/artifacts/$ARTIFACT_ID/zip" \
    -o "$ARTIFACT_ZIP" || exit 1

unset TOKEN

unzip -t "$ARTIFACT_ZIP" >/dev/null 2>&1 || {
    echo "[错误] Artifact ZIP 损坏"
    exit 1
}

# 解压 APK
echo "[7/7] 解压 APK..."

unzip -o "$ARTIFACT_ZIP" -d "$TEMP_DIR/extracted" >/dev/null || exit 1

APK=$(find "$TEMP_DIR/extracted" -type f -name "app-debug.apk" | head -n 1)

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "[错误] 构建产物中没有找到 app-debug.apk"
    exit 1
fi

DEST="$OUTPUT_DIR/RedFingerGameBot.apk"

cp -f "$APK" "$DEST" || exit 1

echo "======================================"
echo "          全部任务完成！"
echo "======================================"
echo "APK 位置：$DEST"
echo "构建编号：$RUN_ID"
echo "提交 SHA：$COMMIT_SHA"

