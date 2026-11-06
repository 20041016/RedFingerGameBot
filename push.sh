
#!/system/bin/sh

# RedFingerGameBot - MT管理器一键推送

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

echo "================================"
echo " RedFingerGameBot GitHub Push"
echo "================================"

echo "[1] 项目目录：$PROJECT_DIR"

if [ ! -e "$PROJECT_DIR/.git" ]; then
    echo "[错误] 当前脚本所在目录没有 .git"
    echo "请把 push.sh 放在项目根目录，与 .git 同级。"
    exit 1
fi

# 查找 Git
if command -v git >/dev/null 2>&1; then
    GIT="$(command -v git)"
elif [ -x "$TERMUX_BIN/git" ]; then
    GIT="$TERMUX_BIN/git"
else
    echo "[错误] 当前执行环境找不到 Git"
    echo "MT管理器无法直接使用 Termux 的 Git。"
    exit 1
fi

export HOME="$TERMUX_HOME"
export PATH="$TERMUX_BIN:/system/bin:/system/xbin:$PATH"

cd "$PROJECT_DIR" || exit 1

echo "[2] 检查 Git 仓库..."
"$GIT" rev-parse --show-toplevel || exit 1

echo "[3] 添加修改..."
"$GIT" add -A || exit 1

if ! "$GIT" diff --cached --quiet; then
    "$GIT" commit -m "Auto update: $(date '+%Y-%m-%d %H:%M:%S')" || exit 1
else
    echo "没有新的修改。"
fi

echo "[4] 同步远程仓库..."
"$GIT" pull --rebase origin main || {
    echo "[错误] 同步失败，请检查冲突或网络。"
    exit 1
}

echo "[5] 推送 GitHub..."
"$GIT" push origin main || {
    echo "[错误] 推送失败。"
    exit 1
}

echo "================================"
echo "         推送成功！"
echo "================================"
