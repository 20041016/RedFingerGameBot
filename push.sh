#!/data/data/com.termux/files/usr/bin/bash

# RedFingerGameBot 一键推送脚本

PROJECT_DIR="/storage/emulated/0/RedFingerGameBot_V0_9_3"
BRANCH="main"

cd "$PROJECT_DIR" || exit 1

echo "================================"
echo " RedFingerGameBot 一键推送工具"
echo "================================"

# 检查 Git 仓库
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[错误] 当前目录不是 Git 仓库"
    exit 1
fi

# 检查远程仓库
if ! git remote get-url origin >/dev/null 2>&1; then
    echo "[错误] 未配置 GitHub 远程仓库"
    exit 1
fi

# 切换到 main 分支
git checkout "$BRANCH" || exit 1

# 添加全部修改
echo "[1/4] 正在添加文件..."
git add -A || exit 1

# 检查是否有修改
if git diff --cached --quiet; then
    echo "[提示] 没有新的修改需要提交"
else
    # 自动生成提交信息
    COMMIT_MSG="Auto update: $(date '+%Y-%m-%d %H:%M:%S')"

    echo "[2/4] 正在提交..."
    git commit -m "$COMMIT_MSG" || exit 1
fi

# 同步远程更新
echo "[3/4] 正在同步 GitHub..."
git pull --rebase origin "$BRANCH" || {
    echo ""
    echo "[错误] 同步失败，可能存在文件冲突"
    echo "请先解决冲突，再运行 ./push.sh"
    exit 1
}

# 推送
echo "[4/4] 正在推送 GitHub..."
git push origin "$BRANCH" || {
    echo "[错误] 推送失败，请检查网络或 GitHub 登录状态"
    exit 1
}

echo ""
echo "================================"
echo "       推送成功！"
echo "================================"
