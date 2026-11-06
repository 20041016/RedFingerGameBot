
#!/system/bin/sh

PROJECT_DIR="/storage/emulated/0/RedFingerGameBot_V0_9_3"
DOWNLOAD_DIR="/storage/emulated/0/Download"
BRANCH="main"

TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

export HOME="$TERMUX_HOME"
export PATH="$TERMUX_BIN:/system/bin:/system/xbin:$PATH"

echo "================================"
echo " RedFingerGameBot 一键更新推送"
echo "================================"

if command -v git >/dev/null 2>&1; then
    GIT="$(command -v git)"
elif [ -x "$TERMUX_BIN/git" ]; then
    GIT="$TERMUX_BIN/git"
else
    echo "[错误] 找不到 Git"
    exit 1
fi

cd "$PROJECT_DIR" || exit 1

if ! "$GIT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[错误] 不是 Git 仓库"
    exit 1
fi

echo "[1/6] 查找最新 ZIP..."

LATEST_ZIP=$(ls -t "$DOWNLOAD_DIR"/*.zip 2>/dev/null | head -n 1)

if [ -z "$LATEST_ZIP" ] || [ ! -f "$LATEST_ZIP" ]; then
    echo "[错误] 没有找到 ZIP"
    exit 1
fi

echo "找到：$(basename "$LATEST_ZIP")"

echo "[2/6] 检查 ZIP..."

if ! command -v unzip >/dev/null 2>&1; then
    if [ -x "$TERMUX_BIN/unzip" ]; then
        UNZIP="$TERMUX_BIN/unzip"
    else
        echo "[错误] 找不到 unzip，无法检查压缩包"
        exit 1
    fi
else
    UNZIP="$(command -v unzip)"
fi

"$UNZIP" -t "$LATEST_ZIP" >/dev/null 2>&1 || {
    echo "[错误] ZIP 文件损坏"
    exit 1
}

echo "[3/6] 保存当前本地修改..."

"$GIT" add -A || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Save local changes before update" || exit 1
fi

echo "[4/6] 同步 GitHub..."

"$GIT" pull --rebase origin "$BRANCH" || {
    echo "[错误] 同步失败，请检查 Git 冲突"
    exit 1
}

echo "[5/6] 替换 source.zip..."

cp -f "$LATEST_ZIP" "$PROJECT_DIR/source.zip" || exit 1

"$GIT" add source.zip || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Update source.zip: $(date '+%Y-%m-%d %H:%M:%S')" || exit 1
fi

echo "[6/6] 推送 GitHub..."

"$GIT" push origin "$BRANCH" || exit 1

echo "================================"
echo "          推送成功！"
echo "================================"
echo "上传：$(basename "$LATEST_ZIP")"
