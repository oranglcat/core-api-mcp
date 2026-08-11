#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$SCRIPT_DIR/deploy"
MCP_TARGET="$SCRIPT_DIR/target"

echo "============================================"
echo "  MCP Adapter 部署包构建脚本"
echo "============================================"

# =========================================================================
# Step 1: 编译 mcp-adapter → fat JAR
# =========================================================================
echo ""
echo "[1/4] 编译 mcp-adapter (Spring Boot 3.4, Port 8124)..."
cd "$SCRIPT_DIR"
mvn clean package -DskipTests
echo "  ✓ 编译完成"

# =========================================================================
# Step 2: 准备 deploy/ 目录并复制构建产物
# =========================================================================
echo ""
echo "[2/4] 准备 deploy/ 目录..."
rm -rf "$DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR/logs"

# ---- Fat JAR ----
cp "$MCP_TARGET/mcp-adapter-1.0.0-SNAPSHOT.jar" "$DEPLOY_DIR/mcp-adapter.jar"
echo "  ✓ mcp-adapter.jar"

# ---- MCP 报文模板 ----
if [ -d "$SCRIPT_DIR/templates" ]; then
    cp -r "$SCRIPT_DIR/templates" "$DEPLOY_DIR/"
    echo "  ✓ templates/ ($(ls "$SCRIPT_DIR/templates/" | wc -l) 个模板)"
else
    mkdir -p "$DEPLOY_DIR/templates"
    echo "  ⚠ templates/ 源目录不存在，已创建空目录"
fi

# =========================================================================
# Step 3: 生成启停脚本
# =========================================================================
echo ""
echo "[3/4] 生成启停脚本..."

# ---- start-mcp.sh ----
cat > "$DEPLOY_DIR/start-mcp.sh" << 'STARTMCP'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

JAR="mcp-adapter.jar"
PID_FILE="mcp-adapter.pid"
LOG_FILE="logs/mcp-adapter.log"

mkdir -p logs

if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "[跳过] MCP Adapter 已在运行 (PID: $(cat $PID_FILE))"
    exit 1
fi

echo -n "启动 MCP Adapter (Port 8124)..."
nohup java -Xms256m -Xmx512m -jar "$JAR" > "$LOG_FILE" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"
echo " PID=$PID"

# 健康检查轮询（最多 60 秒）
for i in $(seq 1 30); do
    sleep 2
    if curl -s --max-time 2 http://localhost:8124/actuator/health 2>/dev/null | grep -q '"status"'; then
        echo "✓ MCP Adapter 健康检查通过 (${i}0s)"
        exit 0
    fi
    printf "."
done
echo ""
echo "⚠ 健康检查超时，请查看日志: tail -f $LOG_FILE"
echo "  手动验证: curl http://localhost:8124/actuator/health"
STARTMCP
chmod +x "$DEPLOY_DIR/start-mcp.sh"
echo "  ✓ start-mcp.sh"

# ---- stop-mcp.sh ----
cat > "$DEPLOY_DIR/stop-mcp.sh" << 'STOPMCP'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="mcp-adapter.pid"
LOG_FILE="logs/mcp-adapter.log"

_stop() {
    local pid=$1
    echo -n "停止 MCP Adapter (PID: $pid)..."
    kill "$pid" 2>/dev/null
    # 优雅关闭等待 10s
    for i in $(seq 1 10); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo " 已停止"
            return 0
        fi
        sleep 1
    done
    # 强制终止
    kill -9 "$pid" 2>/dev/null
    echo " 已强制终止"
}

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        _stop "$PID"
    else
        echo "MCP Adapter 进程已不存在 (stale PID: $PID)"
    fi
    rm -f "$PID_FILE"
else
    # 回退：按 JAR 名查找进程
    PID=$(pgrep -f "mcp-adapter.jar" | head -1)
    if [ -n "$PID" ]; then
        _stop "$PID"
    else
        echo "MCP Adapter 未运行"
    fi
fi

# 轮询确认端口释放
for i in $(seq 1 5); do
    if ! ss -tlnp 2>/dev/null | grep -q ":8124 "; then
        break
    fi
    sleep 1
done
STOPMCP
chmod +x "$DEPLOY_DIR/stop-mcp.sh"
echo "  ✓ stop-mcp.sh"

# =========================================================================
# Step 4: 生成部署说明文档
# =========================================================================
echo ""
echo "[4/4] 生成部署说明..."

cat > "$DEPLOY_DIR/启动说明.txt" << 'README'
╔══════════════════════════════════════════════════════════════╗
║           MCP Adapter — 动态 API 代理 MCP 服务               ║
║                      部署说明文档                            ║
╠══════════════════════════════════════════════════════════════╣
║  版本: 1.0.0-SNAPSHOT    构建时间: BUILD_TIME_PLACEHOLDER   ║
╚══════════════════════════════════════════════════════════════╝

【功能简介】
  将后端 REST 微服务接口动态注册为 MCP 工具，供 LLM 调用。
  - 传输方式: SSE，MCP 端点 /mcp/sse
  - Nacos 服务发现 + 接口白名单过滤
  - 标准报文格式模板（templates/ 目录）

【目录结构】
  deploy/
  ├── mcp-adapter.jar    # MCP 协议适配器 (Spring Boot 3.4, 端口 8124)
  ├── templates/         # 报文 JSON 模板（相对路径，与 jar 同级）
  ├── start-mcp.sh       # 启动脚本
  ├── stop-mcp.sh        # 停止脚本
  └── 启动说明.txt (本文件)

【环境要求】
  - JDK 17+
  - curl（健康检查）
  - 网络可达: Nacos 10.7.30.30:8848
  - 端口可用: 8124

【快速启动】
  1. 赋予执行权限
     chmod +x start-mcp.sh stop-mcp.sh

  2. 启动
     ./start-mcp.sh
     # 等待输出 "✓ MCP Adapter 健康检查通过"

  3. 验证服务
     curl http://localhost:8124/actuator/health

【停止服务】
  ./stop-mcp.sh

【日志查看】
  tail -f logs/mcp-adapter.log

【配置说明】
  - MCP 端口: 8124 (application.yml → server.port)
  - 外置配置: java -jar mcp-adapter.jar
    --spring.config.additional-location=/opt/mcp/application.yml
  - MCP 客户端连接地址: http://服务器IP:8124/mcp/sse
  - 模板目录: templates/（相对路径，服务从 deploy/ 工作目录读取）

【JVM 参数】
  -Xms256m -Xmx512m（可在 start-mcp.sh 中调整）

【故障排查】
  1. 端口冲突: lsof -i :8124
  2. 启动失败: 检查 Nacos 连通性 (curl http://10.7.30.30:8848/nacos)
  3. 模板未生效: 确认 deploy/templates/ 下模板文件名与接口 URL 路径匹配
README

# 替换构建时间占位符
BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S')
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s/BUILD_TIME_PLACEHOLDER/$BUILD_TIME/" "$DEPLOY_DIR/启动说明.txt"
else
    sed -i "s/BUILD_TIME_PLACEHOLDER/$BUILD_TIME/" "$DEPLOY_DIR/启动说明.txt"
fi
echo "  ✓ 启动说明.txt"

# =========================================================================
# 完成
# =========================================================================
echo ""
echo "============================================"
echo "  构建完成！"
echo "  产物目录: $DEPLOY_DIR"
echo "============================================"
echo ""
echo "deploy/ 内容:"
find "$DEPLOY_DIR" -maxdepth 2 -not -path "*/logs/*" | sort | while read f; do
    if [ -f "$f" ]; then
        SIZE=$(du -h "$f" | cut -f1)
        echo "  $(basename "$f")  ($SIZE)"
    elif [ -d "$f" ] && [ "$f" != "$DEPLOY_DIR" ]; then
        COUNT=$(find "$f" -maxdepth 1 -type f | wc -l)
        echo "  $(basename "$f")/  ($COUNT 个文件)"
    fi
done

echo ""
echo "下一步: 将 deploy/ 上传到服务器执行部署"
