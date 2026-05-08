# 本地操作（需安装 Git）
git clone https://github.com/mayufei2026/snail.git
cd snail

# 创建 Jenkinsfile（复制以下内容，保存到仓库根目录）
cat > Jenkinsfile << 'EOF'
pipeline {
    agent any
    stages {
        stage('测试') {
            steps {
                echo '仓库初始化成功！'
            }
        }
    }
}
EOF

# 提交并推送
git add .
git commit -m "初始化仓库，添加 Jenkinsfile"
git push origin master
