# 签名变更说明

本文件记录 AllToolbox（PK管理器）Android 安装包**签名密钥**的变更情况，供开发与用户核对。

## 从哪个版本改了签名

- **自 v1.7.0 起，安装包签名密钥已更换。**
- 旧版本（v1.6.x 及更早）使用旧密钥，签名证书主题为：

  `CN=PK Manager, OU=PKTools, O=PKTools, L=Shenzhen, ST=Guangdong, C=CN`

- 新版本（v1.7.0 / v1.7.1 及后续版本）使用新密钥 `alltoolbox.jks`，签名证书主题为：

  `CN=AllToolbox, OU=AllToolbox, O=AllToolbox, L=X, ST=X, C=CN`

## 影响与升级说明

Android 不允许用**不同签名**覆盖安装已存在的应用。因此：

- 如果你的手机上安装的是**旧签名**版本（v1.6.x 及更早），直接覆盖安装新版本会提示
  「签名异常 / 应用未安装 / INSTALL_FAILED_UPDATE_INCOMPATIBLE」。
- **解决办法：先卸载旧版，再安装新版（v1.7.0+）。**

  > 卸载会清除该应用本地的设置、书签、保险箱等数据。

## 之后

从 v1.7.0 起的所有版本均使用**同一把 `AllToolbox` 密钥**签名，后续兄弟版本间可直接覆盖升级，
不会再出现「签名异常」。

> ⚠️ 请务必妥善保存 `alltoolbox.jks` 与 `keystore.properties`。密钥一旦丢失，
> 将无法发布与原版本签名一致的更新，用户只能卸载重装。