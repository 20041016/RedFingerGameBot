#!/system/bin/sh
# ==========================================
# APK SIGNER
# MT管理器专用批量签名工具
# Root检测 / Java17 / V1+V2+V3
# 自动命名 / 独立输出目录
# 关闭V4 / 自动清理idsig和失败安装包
# 支持本地离线安装 Java17 / apksigner / aapt
# ==========================================
umask 077
# ---------- 签名配置 ----------
KEYSTORE="lizi.jks"
ALIAS="release"
# 请在本机填写自己的密码，不要上传包含密码的脚本
KEYSTORE_PASSWORD="041016"
KEY_PASSWORD="041016"
# ---------- 输出目录 ----------
OUTPUT_DIR="/storage/emulated/0/AAA已签名"
# ---------- 环境配置 ----------
TENV="/data/local/tmp/tenv"
REPO="https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main"
BS_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)
[ -n "$SCRIPT_DIR" ] && cd "$SCRIPT_DIR" || {
    echo "无法确定脚本目录"
    exit 1
}
# ---------- 颜色 ----------
R='\033[0m'
B='\033[1m'
RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
CYAN='\033[36m'
GRAY='\033[90m'
ok()   { printf "  ${GREEN}✔${R} %s\n" "$1"; }
info() { printf "  ${CYAN}›${R} %s\n" "$1"; }
warn() { printf "  ${YELLOW}!${R} %s\n" "$1"; }
fail() { printf "  ${RED}✘${R} %s\n" "$1"; }
# ---------- 临时文件 ----------
LIST="$SCRIPT_DIR/.apk_list"
SEL="$SCRIPT_DIR/.apk_sel"
CONF="$SCRIPT_DIR/.mt_sign.conf"
PWF="$SCRIPT_DIR/.mt_sign.pw"
INNER="$SCRIPT_DIR/.mt_sign.sh"
cleanup_outer() {
    rm -f "$LIST" "$SEL" "$CONF" "$PWF" "$INNER"
    rm -f "$SCRIPT_DIR/.mt_sign_fail"
    rm -f /data/local/tmp/mt_sign.sh
    rm -f /data/local/tmp/mt_sign.conf
    rm -f /data/local/tmp/mt_sign.pw
    rm -f /data/local/tmp/mt_sign_fail
    rm -rf /data/local/tmp/mt_sign_tmp
    rm -rf /data/local/tmp/tenv_dl
}
trap cleanup_outer EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
command -v clear >/dev/null 2>&1 && clear 2>/dev/null
printf "\n${CYAN}${B}             APK SIGNER${R}\n"
printf "${GRAY}       批量签名工具 · MT管理器${R}\n\n"
# ==========================================
# 1. Root检测
# ==========================================
UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" != "0" ]; then
    fail "当前脚本没有 Root 权限"
    printf "  当前 UID：%s\n" "${UID_NOW:-未知}"
    printf "  请使用 Root 身份运行此脚本\n\n"
    exit 1
fi
ok "Root 权限正常"
# ==========================================
# 2. 扫描APK
# ==========================================
: > "$LIST"
for f in "$SCRIPT_DIR"/*.apk; do
    [ -f "$f" ] || continue
    basename "$f" >> "$LIST"
done
if [ ! -s "$LIST" ]; then
    warn "当前目录没有 APK 文件"
    exit 1
fi
TOTAL=$(wc -l < "$LIST" | tr -d ' ')
printf "\n${B}发现 %s 个 APK：${R}\n\n" "$TOTAL"
i=0
while IFS= read -r f; do
    i=$((i + 1))
    SIZE=$(wc -c < "$SCRIPT_DIR/$f" 2>/dev/null | tr -d ' ')
    SIZE=$(awk -v s="${SIZE:-0}" 'BEGIN {
        if (s >= 1048576) printf "%.1f MB", s/1048576
        else printf "%.0f KB", s/1024
    }')
    printf "  [%d] %s  ${GRAY}(%s)${R}\n" "$i" "$f" "$SIZE"
done < "$LIST"
# ==========================================
# 3. 选择文件
# ==========================================
printf "\n输入序号，多个序号用空格或逗号分隔\n"
printf "输入 0 或 all 表示全部\n"
printf "${CYAN}选择：${R}"
read -r CHOICE
[ -n "$CHOICE" ] || {
    fail "没有选择文件"
    exit 1
}
: > "$SEL"
case "$CHOICE" in
    0|all|ALL|All)
        cp "$LIST" "$SEL"
        ;;
    *)
        CHOICE=$(printf '%s' "$CHOICE" | tr ',' ' ')
        for n in $CHOICE; do
            case "$n" in
                ''|*[!0-9]*) continue ;;
            esac
            if [ "$n" -ge 1 ] 2>/dev/null &&
               [ "$n" -le "$TOTAL" ] 2>/dev/null; then
                sed -n "${n}p" "$LIST" >> "$SEL"
            fi
        done
        if [ -s "$SEL" ]; then
            awk '!seen[$0]++' "$SEL" > "$SEL.tmp"
            mv "$SEL.tmp" "$SEL"
        fi
        ;;
esac
[ -s "$SEL" ] || {
    fail "没有选择有效文件"
    exit 1
}
SELECTED=$(wc -l < "$SEL" | tr -d ' ')
ok "已选择 $SELECTED 个文件"
# ==========================================
# 4. 检查密钥库
# ==========================================
if [ ! -f "$SCRIPT_DIR/$KEYSTORE" ]; then
    fail "找不到密钥库：$KEYSTORE"
    exit 1
fi
ok "密钥库正常：$KEYSTORE"
# ==========================================
# 5. 生成配置
# ==========================================
shell_quote() {
    printf "'"
    printf "%s" "$1" | sed "s/'/'\\\\''/g"
    printf "'"
}
{
    printf "KEYSTORE=%s\n" "$(shell_quote "$SCRIPT_DIR/$KEYSTORE")"
    printf "ALIAS=%s\n" "$(shell_quote "$ALIAS")"
    printf "OUTPUT_DIR=%s\n" "$(shell_quote "$OUTPUT_DIR")"
    printf "TENV=%s\n" "$(shell_quote "$TENV")"
    printf "REPO=%s\n" "$(shell_quote "$REPO")"
    printf "BS_URL=%s\n" "$(shell_quote "$BS_URL")"
    printf "SCRIPT_DIR=%s\n" "$(shell_quote "$SCRIPT_DIR")"
    printf "SEL=%s\n" "$(shell_quote "$SEL")"
} > "$CONF"
printf '%s\n%s\n' "$KEYSTORE_PASSWORD" "$KEY_PASSWORD" > "$PWF"
chmod 600 "$CONF" "$PWF"
# ==========================================
# 6. 内部签名程序
# ==========================================
cat > "$INNER" <<'INNEREOF'
#!/system/bin/sh
CONF="/data/local/tmp/mt_sign.conf"
PWF="/data/local/tmp/mt_sign.pw"
[ -f "$CONF" ] || exit 1
. "$CONF"
R='\033[0m'
B='\033[1m'
RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
CYAN='\033[36m'
GRAY='\033[90m'
ok()   { printf "  ${GREEN}✔${R} %s\n" "$1"; }
info() { printf "  ${CYAN}›${R} %s\n" "$1"; }
warn() { printf "  ${YELLOW}!${R} %s\n" "$1"; }
fail() { printf "  ${RED}✘${R} %s\n" "$1"; }
[ "$(id -u 2>/dev/null)" = "0" ] || {
    fail "内部程序不是 Root 身份"
    exit 1
}
DL="/data/local/tmp/tenv_dl"
TMPD="/data/local/tmp/mt_sign_tmp"
FAILF="/data/local/tmp/mt_sign_fail"
cleanup_inner() {
    rm -rf "$TMPD" "$DL"
    rm -f "$CONF" "$PWF"
}
trap cleanup_inner EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# ---------- 创建输出目录 ----------
mkdir -p "$OUTPUT_DIR" || {
    fail "无法创建签名输出目录"
    exit 1
}
[ -d "$OUTPUT_DIR" ] && [ -w "$OUTPUT_DIR" ] || {
    fail "签名输出目录不可写"
    exit 1
}
ok "输出目录：$OUTPUT_DIR"
# ---------- 查找curl ----------
MTBIN=""
for d in \
    /data/user/0/bin.mt.plus/files/term/bin \
    /data/data/bin.mt.plus/files/term/bin
do
    [ -d "$d" ] && {
        MTBIN="$d"
        break
    }
done
CURL="$MTBIN/curl"
[ -x "$CURL" ] || CURL="/system/bin/curl"
[ -x "$CURL" ] || {
    fail "找不到 curl"
    exit 1
}
JAVA="$TENV/lib/jvm/java-17-openjdk/bin/java"
JAR="$TENV/lib/apksigner.jar"
# ==========================================
# 7. 安装运行环境
# ==========================================
# ---------- DEB解压（供 Java/apksigner/aapt 共用） ----------
unpack_deb() {
    DEB="$1"
    OUT="$2"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    if [ -x "$TENV/bin/dpkg-deb" ]; then
        "$TENV/bin/dpkg-deb" -x "$DEB" "$OUT" 2>/dev/null &&
            return 0
    fi
    for fmt in xz gz; do
        OFF=$(grep -abo "data.tar.$fmt" "$DEB" 2>/dev/null |
            head -1 | cut -d: -f1)
        [ -n "$OFF" ] || continue
        SZ=$(dd if="$DEB" bs=1 skip=$((OFF + 48)) count=10 \
            2>/dev/null | tr -d ' \n\r')
        case "$SZ" in
            ''|*[!0-9]*) continue ;;
        esac
        dd if="$DEB" of="$OUT/data.tar.$fmt" \
            bs=1 skip=$((OFF + 60)) count="$SZ" 2>/dev/null
        cd "$OUT" || continue
        if [ "$fmt" = "xz" ]; then
            "$TENV/bin/xz" -d data.tar.xz 2>/dev/null || {
                cd /
                continue
            }
        else
            gzip -d data.tar.gz 2>/dev/null || {
                cd /
                continue
            }
        fi
        "$TENV/bin/tar" xf data.tar 2>/dev/null ||
            tar xf data.tar 2>/dev/null
        cd /
        rm -f "$OUT"/data.tar*
        return 0
    done
    return 1
}
if [ ! -x "$JAVA" ] || [ ! -f "$JAR" ]; then
    info "正在准备 Java 17 和 apksigner"
    mkdir -p "$TENV" "$DL"
    # ---------- Bootstrap ----------
    if [ ! -x "$TENV/bin/bash" ]; then
        BSC="/data/local/tmp/bootstrap-aarch64.zip"
        if [ -f "$BSC" ] &&
           [ "$(wc -c < "$BSC")" -gt 1000000 ]; then
            cp "$BSC" "$DL/bs.zip"
        else
            "$CURL" -fL --retry 2 --max-time 300 \
                -o "$DL/bs.zip" "$BS_URL" || {
                fail "Bootstrap 下载失败"
                exit 1
            }
            cp "$DL/bs.zip" "$BSC"
        fi
        unzip -q -o "$DL/bs.zip" -d "$TENV" || {
            fail "Bootstrap 解压失败"
            exit 1
        }
        if [ -f "$TENV/SYMLINKS" ]; then
            while read -r SRC DST; do
                [ -n "$SRC" ] && [ -n "$DST" ] || continue
                [ -e "$TENV/$SRC" ] && continue
                ln -sf "$DST" "$TENV/$SRC" 2>/dev/null
            done < "$TENV/SYMLINKS"
        fi
        [ -e "$TENV/usr" ] || ln -sf . "$TENV/usr" 2>/dev/null
    fi
    export PREFIX="$TENV"
    export HOME="/data/local/tmp/tenv_home"
    export PATH="$TENV/bin:$PATH"
    mkdir -p "$HOME"
    # ---------- Java 17 ----------
    if [ ! -x "$JAVA" ]; then
        if [ -f /data/local/tmp/openjdk-17.deb ] &&
           [ "$(wc -c < /data/local/tmp/openjdk-17.deb)" -gt 10000000 ]; then
            cp /data/local/tmp/openjdk-17.deb "$DL/jdk.deb"
        else
            JDK_DEB=$("$CURL" -sSL --max-time 60 "$REPO/o/openjdk-17/" 2>/dev/null |
                grep -oE 'openjdk-17[^"<> ]*_aarch64\.deb' |
                tail -1)
            [ -n "$JDK_DEB" ] || {
                fail "无法获取 Java 17 下载地址"
                fail "请手动下载 openjdk-17_*_aarch64.deb 放到 /data/local/tmp/openjdk-17.deb 后重试"
                exit 1
            }
            "$CURL" -fL --retry 2 --max-time 300 \
                -o "$DL/jdk.deb" "$REPO/o/openjdk-17/$JDK_DEB" || {
                fail "Java 17 下载失败"
                exit 1
            }
            cp "$DL/jdk.deb" /data/local/tmp/openjdk-17.deb
        fi
        unpack_deb "$DL/jdk.deb" "$DL/jdk_x" || {
            fail "Java 17 解压失败"
            exit 1
        }
        SRCDIR="$DL/jdk_x/data/data/com.termux/files/usr"
        [ -d "$SRCDIR" ] || SRCDIR="$DL/jdk_x/usr"
        [ -d "$SRCDIR" ] || {
            fail "Java 17 文件结构异常"
            exit 1
        }
        cp -a "$SRCDIR/." "$TENV/"
    fi
    # ---------- apksigner ----------
    if [ ! -f "$JAR" ]; then
        if [ -f /data/local/tmp/apksigner.deb ] &&
           [ "$(wc -c < /data/local/tmp/apksigner.deb)" -gt 10000 ]; then
            cp /data/local/tmp/apksigner.deb "$DL/as.deb"
        else
            AS_DEB=$("$CURL" -sSL --max-time 60 "$REPO/a/apksigner/" 2>/dev/null |
                grep -oE 'apksigner[^"<> ]*\.deb' |
                tail -1)
            [ -n "$AS_DEB" ] || {
                fail "无法获取 apksigner 下载地址"
                fail "请手动下载 apksigner deb 放到 /data/local/tmp/apksigner.deb 后重试"
                exit 1
            }
            "$CURL" -fL --retry 2 --max-time 300 \
                -o "$DL/as.deb" "$REPO/a/apksigner/$AS_DEB" || {
                fail "apksigner 下载失败"
                exit 1
            }
            cp "$DL/as.deb" /data/local/tmp/apksigner.deb 2>/dev/null
        fi
        unpack_deb "$DL/as.deb" "$DL/as_x" || {
            fail "apksigner 解压失败"
            exit 1
        }
        FOUND=$(find "$DL/as_x" -name apksigner.jar | head -1)
        [ -n "$FOUND" ] || {
            fail "找不到 apksigner.jar"
            exit 1
        }
        mkdir -p "$TENV/lib"
        cp "$FOUND" "$JAR"
    fi
fi
# ---------- 环境变量 ----------
export PREFIX="$TENV"
export HOME="/data/local/tmp/tenv_home"
export PATH="$TENV/bin:$PATH"
export LD_LIBRARY_PATH="$TENV/lib:$TENV/lib/jvm/java-17-openjdk/lib:$TENV/lib/jvm/java-17-openjdk/lib/server:$LD_LIBRARY_PATH"
[ -x "$JAVA" ] || {
    fail "Java 17 不可用"
    exit 1
}
[ -f "$JAR" ] || {
    fail "apksigner 不可用"
    exit 1
}
ok "签名环境就绪"
# ==========================================
# 7.5 安装 aapt（可选，用于重名时读取包名末段）
# ==========================================
AAPT_DEST="$TENV/bin/aapt"
if [ ! -x "$AAPT_DEST" ]; then
    info "正在准备 aapt"
    mkdir -p "$DL" "$TENV/bin"
    _AAPT_OK=0
    if [ -f /data/local/tmp/aapt.deb ] &&
       [ "$(wc -c < /data/local/tmp/aapt.deb)" -gt 10000 ]; then
        cp /data/local/tmp/aapt.deb "$DL/aapt.deb" && _AAPT_OK=1
    else
        _AAPT_DEB=$("$CURL" -sSL --max-time 60 "$REPO/a/aapt/" 2>/dev/null |
            grep -oE 'aapt[^"<> ]*_aarch64\.deb' | tail -1)
        if [ -n "$_AAPT_DEB" ]; then
            if "$CURL" -fL --retry 2 --max-time 120 \
                -o "$DL/aapt.deb" "$REPO/a/aapt/$_AAPT_DEB"; then
                _AAPT_OK=1
                cp "$DL/aapt.deb" /data/local/tmp/aapt.deb 2>/dev/null
            fi
        fi
    fi
    if [ "$_AAPT_OK" -eq 1 ] && unpack_deb "$DL/aapt.deb" "$DL/aapt_x"; then
        _FOUND_AAPT=$(find "$DL/aapt_x" -name aapt -type f | head -1)
        if [ -n "$_FOUND_AAPT" ]; then
            cp "$_FOUND_AAPT" "$AAPT_DEST"
            chmod 755 "$AAPT_DEST"
            if "$AAPT_DEST" version >/dev/null 2>&1; then
                ok "aapt 就绪"
            else
                warn "aapt 已安装但运行失败（可能缺依赖库），重名时回退到备用命名"
            fi
        else
            warn "aapt deb 中未找到 aapt 可执行文件"
        fi
    else
        warn "aapt 下载或解压失败（不影响签名，仅重名时后缀使用备用名）"
    fi
fi
# ==========================================
# 8. 查找aapt，用于读取APK包名
# ==========================================
AAPT=""
for candidate in \
    "$TENV/bin/aapt" \
    "$MTBIN/aapt" \
    /system/bin/aapt
do
    if [ -x "$candidate" ]; then
        AAPT="$candidate"
        break
    fi
done
if [ -z "$AAPT" ]; then
    AAPT=$(command -v aapt 2>/dev/null)
fi
if [ -n "$AAPT" ] && [ -x "$AAPT" ]; then
    ok "APK包名读取工具就绪"
else
    warn "没有找到 aapt，发生重名时将使用备用名称"
    AAPT=""
fi
# ---------- 读取包名 ----------
get_package_part() {
    APK="$1"
    PKG=""
    if [ -n "$AAPT" ]; then
        PKG=$("$AAPT" dump badging "$APK" 2>/dev/null |
            sed -n "s/^package: name='\([^']*\)'.*/\1/p" |
            head -1)
    fi
    if [ -n "$PKG" ]; then
        PART=$(printf '%s' "$PKG" | awk -F. '{print $NF}')
        PART=$(printf '%s' "$PART" | tr -cd 'A-Za-z0-9_-')
    else
        PART=""
    fi
    [ -n "$PART" ] || PART="app"
    printf '%s' "$PART"
}
# ==========================================
# ==========================================
# 9. 批量签名
# ==========================================
SP=$(sed -n '1p' "$PWF")
KP=$(sed -n '2p' "$PWF")
[ -n "$KP" ] || KP="$SP"
OK=0
FAIL=0
INDEX=0
TOTAL=$(wc -l < "$SEL" | tr -d ' ')
: > "$FAILF"
printf "\n${B}开始签名：共 %s 个文件${R}\n" "$TOTAL"
while IFS= read -r IN; do
    [ -n "$IN" ] || continue
    INDEX=$((INDEX + 1))
    INPUT="$SCRIPT_DIR/$IN"
    printf "\n${CYAN}[%s/%s]${R} %s\n" "$INDEX" "$TOTAL" "$IN"
    if [ ! -f "$INPUT" ]; then
        fail "源文件不存在"
        FAIL=$((FAIL + 1))
        echo "$IN" >> "$FAILF"
        continue
    fi
    # ---------- 自动生成输出名称 ----------
    # 1) 去掉 .apk 后缀
    # 2) 剥离文件名末尾的版本号（_3.0 / -v3.0 / 3.0.1 / _v1.2 等）
    # 3) 默认输出 <干净名>.apk
    # 4) 若已存在同名文件，则追加 <干净名>_<包名末段>.apk
    # 5) 若仍重复，追加 _2 / _3 ...
    RAW="${IN%.apk}"
    CLEAN=$(printf '%s' "$RAW" | sed -E '
        s/[ _-]v?[0-9]+(\.[0-9]+)*$//;
        s/[ _-]v[0-9]+(\.[0-9]+)*$//;
        s/[ _-][0-9]+(\.[0-9]+)*[ _-][A-Za-z0-9_-]+$//;
    ')
    [ -n "$CLEAN" ] || CLEAN="$RAW"
    OUT="${CLEAN}.apk"
    OUTPUT="$OUTPUT_DIR/$OUT"
    if [ -e "$OUTPUT" ]; then
        PKG_PART=$(get_package_part "$INPUT")
        OUT="${CLEAN}_${PKG_PART}.apk"
        OUTPUT="$OUTPUT_DIR/$OUT"
        N=2
        while [ -e "$OUTPUT" ]; do
            OUT="${CLEAN}_${PKG_PART}_${N}.apk"
            OUTPUT="$OUTPUT_DIR/$OUT"
            N=$((N + 1))
        done
    fi
    IDSIG="${OUTPUT}.idsig"
    printf "  输出名称：%s\n" "$OUT"
    # ---------- 准备临时工作目录 ----------
    rm -rf "$TMPD"
    mkdir -p "$TMPD"
    W="$TMPD/work.apk"
    if ! cp "$INPUT" "$W"; then
        fail "复制源 APK 失败"
        FAIL=$((FAIL + 1))
        echo "$IN" >> "$FAILF"
        rm -f "$OUTPUT" "$IDSIG"
        rm -rf "$TMPD"
        continue
    fi
    # ---------- 签名 ----------
    SIGN_OUTPUT=$(
        MT_STORE_PASS="$SP" MT_KEY_PASS="$KP" \
        "$JAVA" -jar "$JAR" sign \
            --ks "$KEYSTORE" \
            --ks-key-alias "$ALIAS" \
            --ks-pass env:MT_STORE_PASS \
            --key-pass env:MT_KEY_PASS \
            --v1-signing-enabled true \
            --v4-signing-enabled false \
            --verbose \
            --out "$OUTPUT" "$W" 2>&1
    )
    SIGN_STATUS=$?
    if [ "$SIGN_STATUS" -ne 0 ] || [ ! -s "$OUTPUT" ]; then
        fail "签名失败，已清理输出文件"
        [ -n "$SIGN_OUTPUT" ] && printf "%s\n" "$SIGN_OUTPUT"
        FAIL=$((FAIL + 1))
        echo "$IN" >> "$FAILF"
        rm -f "$OUTPUT" "$IDSIG"
        rm -rf "$TMPD"
        continue
    fi
    # ---------- 验证 ----------
    VERIFY_OUTPUT=$(
        "$JAVA" -jar "$JAR" verify \
            --verbose \
            --print-certs \
            "$OUTPUT" 2>&1
    )
    VERIFY_STATUS=$?
    if [ "$VERIFY_STATUS" -eq 0 ]; then
        rm -f "$IDSIG"
        ok "签名成功：$OUTPUT"
        OK=$((OK + 1))
    else
        fail "验证失败，已删除失败安装包及 idsig"
        [ -n "$VERIFY_OUTPUT" ] && printf "%s\n" "$VERIFY_OUTPUT"
        FAIL=$((FAIL + 1))
        echo "$IN" >> "$FAILF"
        rm -f "$OUTPUT" "$IDSIG"
    fi
    rm -rf "$TMPD"
done < "$SEL"
# ==========================================
# 10. 结果统计
# ==========================================
printf "\n${B}========== 签名结果 ==========${R}\n"
printf "  ${GREEN}成功：%s${R}\n" "$OK"
printf "  ${RED}失败：%s${R}\n" "$FAIL"
printf "\n${CYAN}签名文件保存位置：${R}\n"
printf "  %s\n" "$OUTPUT_DIR"
if [ "$FAIL" -gt 0 ]; then
    printf "\n${YELLOW}失败文件：${R}\n"
    while IFS= read -r f; do
        [ -n "$f" ] && printf "  - %s\n" "$f"
    done < "$FAILF"
fi
printf "\n"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
INNEREOF
chmod 700 "$INNER"
# ==========================================
# 11. 启动
# ==========================================
info "正在启动签名程序"
cp "$INNER" /data/local/tmp/mt_sign.sh || {
    fail "复制签名程序失败"
    exit 1
}
cp "$CONF" /data/local/tmp/mt_sign.conf || {
    fail "复制配置失败"
    exit 1
}
cp "$PWF" /data/local/tmp/mt_sign.pw || {
    fail "复制密码文件失败"
    exit 1
}
chmod 700 /data/local/tmp/mt_sign.sh
chmod 600 /data/local/tmp/mt_sign.conf /data/local/tmp/mt_sign.pw
sh /data/local/tmp/mt_sign.sh
STATUS=$?
# ==========================================
# 12. 清理
# ==========================================
cleanup_outer
printf "\n"
if [ "$STATUS" -eq 0 ]; then
    ok "任务结束"
else
    fail "签名任务异常退出，代码：$STATUS"
fi
printf "${GRAY}原始 APK、成功签名 APK、密钥库和环境缓存已保留。${R}\n\n"
exit "$STATUS"