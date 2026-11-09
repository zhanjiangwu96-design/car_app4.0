# 麦轮小车遥控 App (Android, Kotlin)

通过 BLE 连接 KT6368A，每 50ms 发送一帧 8 字节摇杆协议：`DD 77 X Y A B C D`
(X 右正、Y 上正，范围 -100..100，补码；A/B/C/D 按下 1 / 松开 0)。
与小车固件 (McMecanumCar) 中的解析完全对应。

## 编译 (Android Studio)
1. 安装 Android Studio (自带 JDK 17)。
2. File > Open，选择本目录 `McMecanumCarApp`，等待 Gradle 同步 (首次需要联网下载)。
3. 手机打开"开发者选项 > USB 调试"，数据线连接，点击 Run；
   或 Build > Build Bundle(s) / APK(s) > Build APK(s) 生成 apk，拷到手机安装。
   apk 位置: app/build/outputs/apk/debug/app-debug.apk

## 使用
- 先在手机系统设置里**不要**手动配对；直接打开 App，点"连接"，授予蓝牙权限。
- 自动连接名为 `w` 的设备 (TARGET_NAME 在 MainActivity.kt 里修改)；没找到会弹出列表手动选。
- 左边摇杆控制前后/左右平移/斜移，右边 A=左转 B=右转 C=加速 D=减速。
- 注意: 小程序和本 App 不能同时连接小车。
- Android 11 及以下扫描蓝牙需要打开手机的"位置信息"开关。

## 不装 Android Studio, 用 GitHub 在线编译 APK
1. 注册/登录 GitHub，新建一个仓库 (Private 也可以)。
2. 把本目录里的**所有文件(包括隐藏的 .github 文件夹)**上传到仓库根目录。
3. 打开仓库的 Actions 页面，等 "Build APK" 跑完 (约 3~5 分钟)；
   也可以点 "Build APK" > Run workflow 手动触发。
4. 进入这次运行的页面，在底部 Artifacts 下载 McMecanumCar-apk，解压得到 app-debug.apk。
5. 把 apk 发到手机安装 (需要允许"安装未知应用")。
